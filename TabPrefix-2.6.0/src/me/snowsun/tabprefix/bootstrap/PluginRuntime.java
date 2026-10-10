package me.snowsun.tabprefix.bootstrap;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.application.port.CoreControl;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.audit.AuditService;
import me.snowsun.tabprefix.infrastructure.bukkit.MainThreadDispatcher;
import me.snowsun.tabprefix.infrastructure.luckperms.LuckPermsBridge;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.persistence.*;
import me.snowsun.tabprefix.infrastructure.platform.PlatformInfo;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.infrastructure.web.*;
import me.snowsun.tabprefix.presentation.command.*;
import me.snowsun.tabprefix.presentation.display.*;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.util.*;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Composition root: owns every adapter, bounded worker and listener lifecycle. */
public final class PluginRuntime implements CoreControl, AutoCloseable {
  private final JavaPlugin plugin;
  private final ConfigurationManager manager;
  private final PlatformInfo platform;
  private final MainThreadDispatcher dispatcher;
  private final MessageService messages;
  private final SqliteDatabase database;
  private final PrefixService prefixes;
  private final GraphicService graphics;
  private final LuckPermsBridge luck;
  private final MediaStore media;
  private final PackBuilder builder;
  private final ResourcePackService packs;
  private final EditorServer editor;
  private final WebAddresses addresses;
  private final AuditService audit;
  private final PackDelivery delivery;
  private final PrefixController display;
  private final DisplayService displaySettings;
  private final DisplayController appearance;
  private final PlayerEventListener events;
  private final FeatureCommands commands;
  private final AsyncWorker maintenance = new AsyncWorker("Maintenance", 1, 4);
  private final AtomicBoolean closed = new AtomicBoolean(), cleaning = new AtomicBoolean();
  private volatile ConfigurationSnapshot config;
  private volatile boolean started;
  private BukkitTask cleanup;

  public PluginRuntime(
      JavaPlugin plugin,
      ConfigurationManager manager,
      ConfigurationSnapshot config,
      PlatformInfo platform,
      Object api)
      throws IOException {
    this.plugin = plugin;
    this.manager = manager;
    this.config = config;
    this.platform = platform;
    dispatcher = new MainThreadDispatcher(plugin);
    messages = new MessageService(plugin, config);
    database = new SqliteDatabase(config.settings.databaseFile, plugin.getLogger());
    prefixes = new PrefixService(new SqliteGroupPrefixRepository(database));
    graphics =
        new GraphicService(
            new SqliteGraphicRepository(database), prefixes, config.settings.features);
    luck = new LuckPermsBridge(plugin, api);
    media = new MediaStore(config.settings);
    audit = new AuditService(plugin, messages, dispatcher, database, config.settings);
    addresses = new WebAddresses(config.settings.features, plugin.getServer().getIp());
    builder =
        new PackBuilder(
            config.settings, media, platform.minecraft, () -> addresses.base(null, null));
    packs = new ResourcePackService(builder, graphics, audit);
    editor =
        new EditorServer(
            config.settings,
            graphics,
            prefixes,
            media,
            packs,
            audit,
            addresses,
            plugin.getLogger());
    delivery = new PackDelivery(plugin, packs, messages, audit, config.settings);
    delivery.urls(
        (player, revision) ->
            addresses.packUrl(
                player.getUniqueId(),
                player.getAddress() == null ? null : player.getAddress().getAddress(),
                revision));
    display =
        new PrefixController(
            plugin,
            prefixes,
            graphics,
            luck,
            delivery,
            config.settings,
            new TabListView(config.settings.minecraft));
    displaySettings = new DisplayService(database, config.settings.features.nameTags);
    luck.localGroups(
        () -> {
          java.util.List<String> names = new java.util.ArrayList<>();
          for (DisplayDesign.GroupStyle group : displaySettings.snapshot().design.groupStyles)
            names.add(group.group);
          return names;
        });
    appearance = new DisplayController(plugin, displaySettings, display, config.settings);
    editor.displays(
        displaySettings,
        (id, permission) -> {
          CompletableFuture<Boolean> future = new CompletableFuture<>();
          dispatcher.execute(
              () -> {
                org.bukkit.entity.Player player = plugin.getServer().getPlayer(id);
                future.complete(
                    ready()
                        && player != null
                        && player.isOnline()
                        && player.hasPermission("tabprefix.use")
                        && player.hasPermission(permission));
              });
          return future;
        },
        (id, design, tick) -> {
          CompletableFuture<com.google.gson.JsonObject> future = new CompletableFuture<>();
          dispatcher.execute(
              () -> {
                org.bukkit.entity.Player player = plugin.getServer().getPlayer(id);
                if (!ready()
                    || player == null
                    || !player.isOnline()
                    || !player.hasPermission("tabprefix.design")) {
                  future.completeExceptionally(
                      new HttpProblem(403, "Player is offline or no longer permitted to preview."));
                  return;
                }
                try {
                  future.complete(appearance.preview(player, design, tick));
                } catch (RuntimeException e) {
                  future.completeExceptionally(e);
                }
              });
          return future;
        });
    editor.displayGroups(
        () -> {
          CompletableFuture<com.google.gson.JsonArray> future = new CompletableFuture<>();
          dispatcher.execute(
              () -> {
                try {
                  future.complete(luck.groupMetadata());
                } catch (RuntimeException e) {
                  future.completeExceptionally(e);
                }
              });
          return future;
        });
    events = new PlayerEventListener(display, dispatcher, plugin);
    commands =
        new FeatureCommands(
            plugin,
            this,
            graphics,
            prefixes,
            luck,
            editor,
            packs,
            delivery,
            audit,
            messages,
            dispatcher,
            () -> this.config.settings);
    delivery.onChanged(display::requestViewer);
    packs.onPublished(
        revision ->
            dispatcher.execute(
                () -> {
                  if (started) {
                    delivery.published();
                    display.requestAll();
                  }
                }));
  }

  public void start() {
    log("lifecycle.starting", Values.of("version", plugin.getDescription().getVersion()));
    plugin
        .getLogger()
        .info(
            "Minecraft "
                + platform.minecraft
                + "; supported: "
                + MinecraftVersion.SUPPORTED
                + "; resource-pack format="
                + ResourcePackFormat.forVersion(platform.minecraft)
                + "; RGB="
                + platform.minecraft.rgb()
                + "; bitmap fonts="
                + platform.minecraft.bitmapFonts());
    if (!platform.minecraft.bitmapFonts())
      plugin
          .getLogger()
          .info(
              "Minecraft 1.12 uses text prefix fallbacks. Images and GIFs are retained for a later"
                  + " server upgrade.");
    CompletableFuture<Void> loading =
        database
            .initialize()
            .thenCompose(v -> prefixes.initialize())
            .thenCompose(v -> graphics.initialize())
            .thenCompose(v -> displaySettings.initialize())
            .thenCompose(v -> audit.initialize())
            .thenCompose(
                v ->
                    maintenance.submit(
                        () -> {
                          media.verify(graphics.snapshot());
                          // Discover the actual bound port before loading or publishing pack URLs.
                          try {
                            editor.start();
                            if (editor.running()
                                && config.settings.features.publicHost.isEmpty()
                                && config.settings.features.editorBase.isEmpty())
                              plugin
                                  .getLogger()
                                  .info(
                                      "Automatic editor links use server network addresses."
                                          + " Same-LAN access needs no router port forwarding;"
                                          + " Internet access requires a reachable public"
                                          + " URL/proxy. Use /lptab linkwifi.");
                          } catch (IOException | RuntimeException error) {
                            plugin
                                .getLogger()
                                .log(
                                    java.util.logging.Level.WARNING,
                                    "Cannot start web editor; text prefixes remain available. "
                                        + editor.diagnostic(),
                                    error);
                          }
                          return null;
                        }))
            .thenCompose(v -> packs.initialize())
            .thenCompose(
                v ->
                    config.settings.features.pack && config.settings.features.autoBuild
                        ? packs.rebuild().thenApply(p -> null)
                        : CompletableFuture.completedFuture(null));
    loading.whenComplete(
        (v, error) ->
            dispatcher.execute(
                () -> {
                  if (error != null) {
                    plugin
                        .getLogger()
                        .log(
                            java.util.logging.Level.SEVERE,
                            "TabPrefix startup failed.",
                            Failures.root(error));
                    plugin.getServer().getPluginManager().disablePlugin(plugin);
                    return;
                  }
                  try {
                    display.start();
                    appearance.start();
                    luck.listen(
                        config.settings.listenForUpdates, display::request, display::requestAll);
                    plugin.getServer().getPluginManager().registerEvents(events, plugin);
                    plugin.getServer().getPluginManager().registerEvents(delivery, plugin);
                    started = true;
                    delivery.start();
                    cleanup =
                        plugin
                            .getServer()
                            .getScheduler()
                            .runTaskTimer(plugin, this::cleanup, 20, 1200);
                    log("lifecycle.started", Collections.emptyMap());
                  } catch (RuntimeException | LinkageError e) {
                    plugin
                        .getLogger()
                        .log(
                            java.util.logging.Level.SEVERE,
                            "Cannot activate TabPrefix adapters.",
                            e);
                    plugin.getServer().getPluginManager().disablePlugin(plugin);
                  }
                }));
  }

  private void cleanup() {
    if (!started || !cleaning.compareAndSet(false, true)) return;
    editor
        .cleanup()
        .thenCompose(v -> graphics.cleanup(System.currentTimeMillis()))
        .thenCompose(
            removed ->
                maintenance.submit(
                    () -> {
                      for (UUID id : removed) media.delete(id);
                      media.cleanup(
                          graphics.snapshot().assets.keySet(), System.currentTimeMillis());
                      return null;
                    }))
        .whenComplete(
            (v, e) -> {
              cleaning.set(false);
              if (e != null) error("Storage cleanup failed.", e);
            });
  }

  @Override
  public void reload() throws Exception {
    ConfigurationSnapshot candidate = manager.load();
    candidate.settings.validatePlatform(platform);
    PluginSettings a = config.settings, b = candidate.settings;
    if (a.luckPermsEnabled != b.luckPermsEnabled
        || !a.databaseFile.equals(b.databaseFile)
        || !a.dataDirectory.equals(b.dataDirectory)
        || !a.tempDirectory.equals(b.tempDirectory)
        || !a.packsDirectory.equals(b.packsDirectory)
        || !a.stagingDirectory.equals(b.stagingDirectory)
        || !a.features.restartKey().equals(b.features.restartKey()))
      throw new IllegalArgumentException(
          "LuckPerms integration, storage, HTTP binding/addresses, glyph geometry/range and code"
              + " format changes require a server restart.");
    messages.reload(candidate);
    config = candidate;
    graphics.reload(b.features);
    media.reload(b);
    builder.reload(b);
    editor.reload(b);
    audit.reload(b);
    delivery.reload(b);
    display.reload(b);
    appearance.reload(b);
    luck.listen(b.listenForUpdates, display::request, display::requestAll);
    if (b.features.pack && b.features.autoBuild)
      packs
          .rebuild()
          .whenComplete(
              (v, e) -> {
                if (e != null) error("Pack rebuild after reload failed.", e);
              });
  }

  @Override
  public boolean ready() {
    return started && !closed.get() && prefixes.ready();
  }

  public MessageService messages() {
    return messages;
  }

  public PrefixService prefixes() {
    return prefixes;
  }

  public LuckPermsBridge luckPerms() {
    return luck;
  }

  public PlatformInfo platform() {
    return platform;
  }

  public MainThreadDispatcher dispatcher() {
    return dispatcher;
  }

  public ExtraCommands commands() {
    return commands;
  }

  @Override
  public void prefixesChanged() {
    display.requestAll();
    if (config.settings.features.pack && config.settings.features.autoBuild)
      packs
          .rebuild()
          .whenComplete(
              (v, e) -> {
                if (e != null) error("Automatic pack rebuild failed.", e);
              });
  }

  @Override
  public RuntimeStatus status() {
    return new RuntimeStatus(
        plugin.getDescription().getVersion(),
        platform.minecraft.toString(),
        platform.name,
        ready(),
        prefixes.count());
  }

  @Override
  public void error(String action, Throwable error) {
    plugin.getLogger().log(java.util.logging.Level.WARNING, action, Failures.root(error));
  }

  @Override
  public void diagnostic(String report) {
    plugin.getLogger().info(report);
  }

  @Override
  public String webDiagnostic() {
    return "permissions="
        + (luck.integrated() ? "LuckPerms" : "Bukkit")
        + "; "
        + editor.diagnostic()
        + "; "
        + appearance.diagnostic();
  }

  private void log(String key, Map<String, String> values) {
    plugin.getLogger().info(messages.plain(key, values));
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    started = false;
    if (cleanup != null) cleanup.cancel();
    close("HTTP", editor::close);
    close("pack delivery", delivery::close);
    close(
        "listeners",
        () -> {
          HandlerList.unregisterAll(events);
          HandlerList.unregisterAll(delivery);
        });
    close("display", display::close);
    close("appearance", appearance::close);
    close("LuckPerms", luck::close);
    close("packs", packs::close);
    close("maintenance", maintenance::close);
    close("audit", audit::close);
    close("text cache", prefixes::close);
    close("SQLite", database::close);
    dispatcher.close();
    close("messages", messages::close);
  }

  private void close(String name, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException | LinkageError e) {
      plugin.getLogger().log(java.util.logging.Level.WARNING, "Cannot close " + name, e);
    }
  }
}
