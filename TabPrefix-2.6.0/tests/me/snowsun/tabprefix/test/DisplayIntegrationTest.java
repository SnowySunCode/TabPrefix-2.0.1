package me.snowsun.tabprefix.test;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import java.io.*;
import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.*;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.VirtualTabPackets;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.persistence.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.infrastructure.web.*;
import me.snowsun.tabprefix.presentation.display.*;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import me.snowsun.tabprefix.util.AtomicFiles;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.minecraft.server.v1_16_R3.*;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

/** Real shaded classes, SQLite, HTTP; native display operations against independent API models. */
public final class DisplayIntegrationTest {
  private static final Logger LOG = Logger.getLogger("TabPrefix-DisplayTest");
  private static int checks;

  private static void check(boolean b, String text) {
    if (!b) throw new AssertionError(text);
    checks++;
  }

  private static <T> T await(CompletableFuture<T> f) throws Exception {
    return f.get(10, TimeUnit.SECONDS);
  }

  private static void reject(Runnable f, String text) {
    try {
      f.run();
      throw new AssertionError("Accepted: " + text);
    } catch (IllegalArgumentException e) {
      checks++;
    }
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]);
    Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "display-");
    try {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.load(project.resolve("resources/config.yml").toFile());
      yaml.set("web.bind-address", "127.0.0.1");
      yaml.set("web.public-host", "127.0.0.1");
      try (ServerSocket socket = new ServerSocket(0)) {
        yaml.set("web.port", socket.getLocalPort());
      }
      PluginSettings settings = new PluginSettings(yaml, root);
      documents(settings, yaml, root);
      boards();
      packets();
      try (SqliteDatabase db = new SqliteDatabase(settings.databaseFile, LOG)) {
        await(db.initialize());
        DisplayService displays = new DisplayService(db, false);
        await(displays.initialize());
        storage(db, displays);
        PrefixService texts = new PrefixService(new SqliteGroupPrefixRepository(db));
        await(texts.initialize());
        GraphicService graphics =
            new GraphicService(new SqliteGraphicRepository(db), texts, settings.features);
        await(graphics.initialize());
        runtime(root, settings, displays, texts, graphics);
        boolean serving = Arrays.asList(args).contains("--serve");
        boolean legacy = Arrays.asList(args).contains("--legacy");
        PluginSettings webSettings =
            serving
                ? new PluginSettings(
                    yaml, root, MinecraftVersion.parse(legacy ? "1.12.2" : "1.21.11"))
                : settings;
        if (legacy) {
          Path font = webSettings.dataDirectory.resolve("legacy-font");
          Files.createDirectories(font);
          Files.write(font.resolve("glyph_sizes.bin"), new byte[65536]);
        }
        MediaStore media = new MediaStore(webSettings);
        PackBuilder builder =
            new PackBuilder(
                webSettings,
                media,
                webSettings.minecraft,
                PublicAddress.resolve(settings.features, ""));
        try (ResourcePackService packs = new ResourcePackService(builder, graphics, (e, v) -> {});
            EditorServer editor =
                new EditorServer(
                    webSettings,
                    graphics,
                    texts,
                    media,
                    packs,
                    (e, v) -> {},
                    PublicAddress.resolve(settings.features, ""),
                    LOG)) {
          await(packs.initialize());
          AtomicBoolean allowed = new AtomicBoolean(true);
          TextRenderer renderer = new TextRenderer(webSettings);
          editor.displays(
              displays,
              (id, permission) -> CompletableFuture.completedFuture(allowed.get()),
              (id, design, tick) ->
                  CompletableFuture.completedFuture(
                      preview(renderer, design, tick, displays.preferences(id))));
          editor.displayGroups(() -> CompletableFuture.completedFuture(sampleGroups()));
          editor.start();
          if (serving) {
            UUID owner = UUID.fromString("a0000000-0000-0000-0000-000000000001");
            JsonObject info = new JsonObject();
            info.addProperty("admin", editor.openStudio(owner, "SnowySun", null, true, true));
            info.addProperty(
                "player", editor.openStudio(UUID.randomUUID(), "Alex", null, false, true));
            info.addProperty(
                "prefix", editor.open(UUID.randomUUID(), "SnowySun", "vip", null, true, true));
            AtomicFiles.write(
                project.resolve(".build/studio-test.json"),
                info.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("STUDIO_READY");
            System.out.flush();
            System.in.read();
          } else http(editor, displays, allowed);
        }
        texts.close();
      }
      System.out.println(
          "PASS: "
              + checks
              + " display checks: validation, animations, persistence, native sidebar/name tags,"
              + " TAB reflection contract and authenticated HTTP.");
    } finally {
      AtomicFiles.deleteTree(root);
    }
  }

  private static void documents(PluginSettings settings, YamlConfiguration yaml, Path root)
      throws Exception {
    DisplayDesign defaults = DisplayDesign.defaults(false);
    check(defaults.slots.size() == 80, "exact fixed grid");
    check(
        !defaults.layoutEnabled && !defaults.sidebarEnabled && !defaults.headerEnabled,
        "upgrade preserves existing visual state until enabled");
    check(
        defaults.json().toString().equals(new DisplayDesign(defaults.json()).json().toString()),
        "round trip");
    JsonObject doc = defaults.json();
    doc.addProperty("refreshTicks", 1);
    reject(() -> new DisplayDesign(doc), "tick floor");
    JsonObject few = defaults.json();
    few.getAsJsonArray("slots").remove(0);
    reject(() -> new DisplayDesign(few), "short grid");
    JsonObject rows = defaults.json();
    for (int i = 0; i < 16; i++) rows.getAsJsonArray("sidebar").add(new JsonObject());
    reject(() -> new DisplayDesign(rows), "sidebar limit");
    JsonObject negative = defaults.json();
    negative.getAsJsonArray("slots").get(0).getAsJsonObject().addProperty("playerIndex", 0);
    reject(() -> new DisplayDesign(negative), "player index");
    JsonObject noninteger = defaults.json();
    noninteger.addProperty("refreshTicks", 4.5);
    reject(() -> new DisplayDesign(noninteger), "fractional ticks");
    JsonObject nan = defaults.json();
    nan.addProperty("refreshTicks", "4");
    reject(() -> new DisplayDesign(nan), "string ticks");
    JsonObject controls = defaults.json();
    controls.addProperty("serverName", "a\nb");
    reject(() -> new DisplayDesign(controls), "embedded line break");
    JsonObject unicode = defaults.json();
    unicode.addProperty("serverName", "\ud800");
    reject(() -> new DisplayDesign(unicode), "broken unicode");
    JsonObject unknown = defaults.json();
    unknown.addProperty("visibility", "OTHER");
    reject(() -> new DisplayDesign(unknown), "unknown native option");
    JsonObject frames = new JsonObject();
    frames.addProperty("animation", "FRAMES");
    reject(() -> new DisplayDesign.Line(frames), "empty frame animation");
    JsonArray f = new JsonArray();
    f.add("a");
    f.add("b");
    frames.add("frames", f);
    frames.addProperty("speed", 2);
    DisplayDesign.Line anim = new DisplayDesign.Line(frames);
    check(
        anim.source(3, true).equals("b") && anim.source(4, true).equals("a"), "frame tick timing");
    check(anim.source(3, false).equals("a"), "paused frames");
    TextRenderer renderer = new TextRenderer(settings);
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("player", "<red>literal</red>");
    vars.put("prefix", "§6[VIP] ");
    vars.put("world", "{player}");
    check(
        renderer
            .plain(renderer.display("<aqua>{player}</aqua>", vars))
            .equals("<red>literal</red>"),
        "MiniMessage placeholder injection blocked");
    check(
        renderer.plain(renderer.display("&a{world}", vars)).equals("{player}"),
        "legacy placeholders replaced once");
    check(
        renderer.legacy(renderer.display("{prefix}{player}", vars)).contains("§6"),
        "prefix style preserved");
    YamlConfiguration plainSettings = new YamlConfiguration();
    plainSettings.loadFromString(yaml.saveToString());
    plainSettings.set("minimessage.enabled", false);
    TextRenderer disabled = new TextRenderer(new PluginSettings(plainSettings, root));
    check(
        disabled
            .plain(disabled.display("<gold>{player}</gold>", vars))
            .equals("<red>literal</red>"),
        "Disabled MiniMessage strips template markup while keeping literal player values");
    check(plain(DisplayAnimation.scroll("§aABCDE", 4, 2, 1)).equals("BCDE"), "scroll movement");
    check(plain(DisplayAnimation.scroll("ABCDEF", 4, 2, 6)).equals("  AB"), "scroll gap and wrap");
    check(DisplayAnimation.scroll("ABC", 4, 2, 3).equals("ABC"), "short lines stay still");
    check(
        plain(DisplayAnimation.scroll("😀😃😄😁😆", 4, 1, 1)).equals("😃😄😁😆"),
        "whole Unicode points");
    String rgb = "§x§1§2§3§4§5§6";
    String scrolled =
        DisplayAnimation.scroll(rgb + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 28, 2, 2);
    check(scrolled.contains(rgb) && plain(scrolled).length() == 28, "RGB survives scrolling");
    check(scrolled.length() < 64, "scroll colours are compressed for sidebar limits");
    check(new PlayerDisplaySettings(new JsonObject()).animations, "preferences default enabled");
    JsonObject preference = new JsonObject();
    preference.addProperty("sidebar", "false");
    reject(() -> new PlayerDisplaySettings(preference), "strict preference types");
    try {
      defaults.header.clear();
      throw new AssertionError("mutable document");
    } catch (UnsupportedOperationException e) {
      checks++;
    }
  }

  private static String plain(String legacy) {
    return legacy.replaceAll("§[0-9a-fklmnorxA-FKLMNORX]", "");
  }

  private static void storage(SqliteDatabase db, DisplayService displays) throws Exception {
    UUID owner = UUID.randomUUID();
    JsonObject j = displays.snapshot().design.json();
    j.addProperty("serverName", "Persisted");
    JsonArray savedBars = new JsonArray();
    savedBars.add(sampleBoss());
    j.add("bossBars", savedBars);
    j.addProperty("tabFormatEnabled", true);
    j.addProperty("nativeSortEnabled", true);
    j.addProperty("sort", "PRIORITY");
    j.add("groupStyles", sampleStyles());
    DisplayService.Snapshot first = await(displays.save(new DisplayDesign(j), 1, owner));
    check(first.revision == 2, "revision increment after commit");
    try {
      await(displays.save(DisplayDesign.defaults(false), 1, owner));
      throw new AssertionError("stale save accepted");
    } catch (ExecutionException e) {
      check(
          e.getCause() instanceof HttpProblem && ((HttpProblem) e.getCause()).status == 409,
          "optimistic stale save conflict");
    }
    check(
        displays.snapshot().design.serverName.equals("Persisted"),
        "conflict preserves current design");
    JsonObject p = new JsonObject();
    p.addProperty("sidebar", false);
    p.addProperty("animations", false);
    p.addProperty("bossBars", false);
    p.addProperty("suffixes", false);
    p.addProperty("sorting", false);
    await(displays.savePreferences(owner, new PlayerDisplaySettings(p)));
    DisplayService reload = new DisplayService(db, true);
    await(reload.initialize());
    check(
        reload.snapshot().design.serverName.equals("Persisted") && reload.snapshot().revision == 2,
        "design survives restart");
    check(
        !reload.preferences(owner).sidebar && !reload.preferences(owner).animations,
        "preferences survive restart");
    check(reload.preferences(UUID.randomUUID()).sidebar, "different owner unaffected");
    check(
        reload.snapshot().design.bossBars.size() == 1
            && reload.snapshot().design.bossBars.get(0).id.equals("welcome"),
        "boss bars survive SQLite reopen");
    check(
        !reload.preferences(owner).bossBars && reload.preferences(UUID.randomUUID()).bossBars,
        "boss preference persists for only its owner");
    check(
        reload.snapshot().design.tabFormatEnabled
            && reload.snapshot().design.nativeSortEnabled
            && reload.snapshot().design.groupStyles.get(0).group.equals("default")
            && reload.snapshot().design.groupStyle("VIP").suffix.text.contains("STAR"),
        "TAB format, suffixes and ordered group styles survive SQLite reopen");
    check(
        !reload.preferences(owner).suffixes
            && !reload.preferences(owner).sorting
            && reload.preferences(UUID.randomUUID()).suffixes
            && reload.preferences(UUID.randomUUID()).sorting,
        "suffix and sorting preferences persist for only their owner");
    CompletableFuture<DisplayService.Snapshot>
        a = displays.save(DisplayDesign.defaults(false), 2, owner),
        b = displays.save(DisplayDesign.defaults(true), 2, owner);
    int success = 0, conflicts = 0;
    for (CompletableFuture<DisplayService.Snapshot> future : Arrays.asList(a, b)) {
      try {
        await(future);
        success++;
      } catch (ExecutionException e) {
        if (e.getCause() instanceof HttpProblem) conflicts++;
        else throw e;
      }
    }
    check(success == 1 && conflicts == 1, "simultaneous saves commit once");
    check(
        await(
                db.execute(
                    c -> {
                      try (java.sql.Statement s = c.createStatement();
                          java.sql.ResultSet r = s.executeQuery("PRAGMA user_version")) {
                        r.next();
                        return r.getInt(1);
                      }
                    }))
            == 3,
        "schema migration includes display tables");
  }

  public interface CraftPlayer extends Player {
    EntityPlayer getHandle();
  }

  private static Player player(String name, UUID id, EntityPlayer entity) {
    return (Player)
        Proxy.newProxyInstance(
            DisplayIntegrationTest.class.getClassLoader(),
            new Class<?>[] {CraftPlayer.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getHandle":
                  return entity;
                case "getUniqueId":
                  return id;
                case "getName":
                  return name;
                case "getPing":
                  return entity.ping;
                case "equals":
                  return proxy == args[0];
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "toString":
                  return name;
                default:
                  return zero(method.getReturnType());
              }
            });
  }

  private static void packets() {
    Logger log = Logger.getLogger("TabPrefix-PacketFixture");
    log.setUseParentHandlers(false);
    GameProfile profile = new GameProfile(UUID.randomUUID(), "Steve");
    profile.getProperties().put("textures", "signed-skin");
    EntityPlayer entity = new EntityPlayer(profile);
    Player viewer = player("Steve", profile.getId(), entity);
    VirtualTabPackets adapter = new VirtualTabPackets(log);
    List<VirtualTabPackets.Cell> cells = new ArrayList<>();
    for (int i = 0; i < 80; i++)
      cells.add(new VirtualTabPackets.Cell("Slot " + i, i == 1 ? viewer : null));
    check(
        adapter.render(viewer, cells, Collections.singleton(viewer), 0),
        "native reflection initialization");
    PacketPlayOutPlayerInfo add = (PacketPlayOutPlayerInfo) entity.playerConnection.packets.get(0);
    check(
        add.action == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER
            && add.entries.size() == 80,
        "80 synthetic entries in one packet");
    check(
        add.entries.stream().noneMatch(e -> e.profile.getId().equals(profile.getId())),
        "real profile UUID retained");
    check(
        add.entries.get(1).profile.getProperties().get("textures").contains("signed-skin"),
        "player skin copied onto synthetic slot");
    check(
        add.entries.get(0).profile.getName().compareTo("0player") < 0,
        "synthetic entries sort before unteamed real players");
    Set<UUID> uuids = new HashSet<>();
    add.entries.forEach(e -> uuids.add(e.profile.getId()));
    check(uuids.size() == 80, "unique stable synthetic UUIDs");
    check(
        adapter.render(viewer, cells, Collections.singleton(viewer), 4)
            && entity.playerConnection.packets.size() == 1,
        "unchanged layout sends no packets");
    cells.set(0, new VirtualTabPackets.Cell("Changed", null));
    adapter.render(viewer, cells, Collections.singleton(viewer), 8);
    PacketPlayOutPlayerInfo update =
        (PacketPlayOutPlayerInfo) entity.playerConnection.packets.get(1);
    check(
        update.action == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.UPDATE_DISPLAY_NAME
            && update.entries.size() == 1,
        "only changed display name sent");
    cells.set(1, new VirtualTabPackets.Cell("Unbound", null));
    adapter.render(viewer, cells, Collections.singleton(viewer), 12);
    check(
        ((PacketPlayOutPlayerInfo) entity.playerConnection.packets.get(2)).action
            == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER,
        "skin rebinding updates profile");
    adapter.restore(viewer, Collections.singleton(viewer));
    PacketPlayOutPlayerInfo remove =
        (PacketPlayOutPlayerInfo) entity.playerConnection.packets.get(3);
    check(
        remove.action == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER
            && remove.entries.size() == 80,
        "restore removes only synthetic entries");
    check(
        entity.playerConnection.packets.stream()
            .noneMatch(
                p ->
                    ((PacketPlayOutPlayerInfo) p).action
                        == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.UPDATE_GAME_MODE),
        "player game modes never spoofed");
    check(entity.gameMode == EnumGamemode.CREATIVE, "world game mode unchanged");
    adapter.restore(viewer, Collections.singleton(viewer));
    check(entity.playerConnection.packets.size() == 4, "repeat restore is safe");
    check(adapter.ping(viewer) == 42, "latency read");
    cells.set(0, new VirtualTabPackets.Cell("Player", viewer));
    adapter.render(viewer, cells, Collections.singleton(viewer), 16);
    entity.ping = 75;
    adapter.render(viewer, cells, Collections.singleton(viewer), 20);
    PacketPlayOutPlayerInfo latency =
        (PacketPlayOutPlayerInfo)
            entity.playerConnection.packets.get(entity.playerConnection.packets.size() - 1);
    check(
        latency.action == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.UPDATE_LATENCY
            && latency.entries.get(0).ping == 75,
        "virtual player latency updates without re-adding profile");
    adapter.restore(viewer, Collections.singleton(viewer));
    entity.playerConnection.fail = true;
    check(
        !adapter.render(viewer, cells, Collections.singleton(viewer), 16) && !adapter.available(),
        "packet errors disable layout and trigger fallback");
  }

  private static Object zero(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == double.class) return 0d;
    if (type == float.class) return 0f;
    if (type == short.class) return (short) 0;
    if (type == byte.class) return (byte) 0;
    if (type == char.class) return '\0';
    return null;
  }

  private static final class Board {
    final Map<String, T> teams = new HashMap<>();
    final Map<String, O> objectives = new HashMap<>();
    Objective active;
    final Scoreboard api =
        (Scoreboard)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Scoreboard.class},
                (proxy, m, a) -> {
                  switch (m.getName()) {
                    case "getObjective":
                      return a[0] instanceof DisplaySlot
                          ? active
                          : objectives.containsKey(a[0]) ? objectives.get(a[0]).api : null;
                    case "getObjectives":
                      {
                        Set<Objective> result = new HashSet<>();
                        objectives.values().forEach(o -> result.add(o.api));
                        return result;
                      }
                    case "getTeams":
                      {
                        Set<Team> result = new HashSet<>();
                        teams.values().forEach(t -> result.add(t.api));
                        return result;
                      }
                    case "registerNewObjective":
                      {
                        O o = new O((String) a[0], a.length > 2 ? (String) a[2] : "Title");
                        objectives.put(o.name, o);
                        return o.api;
                      }
                    case "getTeam":
                      return teams.containsKey(a[0]) ? teams.get(a[0]).api : null;
                    case "registerNewTeam":
                      {
                        T t = new T((String) a[0]);
                        teams.put(t.name, t);
                        return t.api;
                      }
                    case "getEntryTeam":
                      for (T t : teams.values()) if (t.entries.contains(a[0])) return t.api;
                      return null;
                    case "resetScores":
                      objectives.values().forEach(o -> o.scores.remove(a[0]));
                      return null;
                    case "equals":
                      return proxy == a[0];
                    case "hashCode":
                      return System.identityHashCode(proxy);
                    default:
                      return zero(m.getReturnType());
                  }
                });

    final class T {
      final String name;
      String prefix = "", suffix = "";
      final Set<String> entries = new HashSet<>();
      final Map<Team.Option, Team.OptionStatus> options = new EnumMap<>(Team.Option.class);
      final Team api;

      T(String name) {
        this.name = name;
        api =
            (Team)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Team.class},
                    (p, m, a) -> {
                      switch (m.getName()) {
                        case "getName":
                          return name;
                        case "getPrefix":
                          return prefix;
                        case "setPrefix":
                          prefix = (String) a[0];
                          return null;
                        case "getSuffix":
                          return suffix;
                        case "setSuffix":
                          suffix = (String) a[0];
                          return null;
                        case "addEntry":
                          for (T other : teams.values()) other.entries.remove((String) a[0]);
                          entries.add((String) a[0]);
                          return null;
                        case "hasEntry":
                          return entries.contains(a[0]);
                        case "getOption":
                          return options.getOrDefault(a[0], Team.OptionStatus.ALWAYS);
                        case "setOption":
                          options.put((Team.Option) a[0], (Team.OptionStatus) a[1]);
                          return null;
                        case "unregister":
                          teams.remove(name);
                          return null;
                        case "equals":
                          return p == a[0];
                        case "hashCode":
                          return System.identityHashCode(p);
                        default:
                          return zero(m.getReturnType());
                      }
                    });
      }
    }

    final class O {
      final String name;
      String title;
      final Map<String, Integer> scores = new HashMap<>();
      final Objective api;

      O(String name, String title) {
        this.name = name;
        this.title = title;
        api =
            (Objective)
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {Objective.class},
                    (p, m, a) -> {
                      switch (m.getName()) {
                        case "getDisplayName":
                          return this.title;
                        case "setDisplayName":
                          this.title = (String) a[0];
                          return null;
                        case "setDisplaySlot":
                          active = (Objective) p;
                          return null;
                        case "unregister":
                          objectives.remove(name);
                          if (active == p) active = null;
                          return null;
                        case "getScore":
                          {
                            String entry = (String) a[0];
                            return Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {Score.class},
                                (s, sm, sa) -> {
                                  if (sm.getName().equals("getScore"))
                                    return scores.getOrDefault(entry, 0);
                                  if (sm.getName().equals("setScore")) {
                                    scores.put(entry, (Integer) sa[0]);
                                    return null;
                                  }
                                  return zero(sm.getReturnType());
                                });
                          }
                        case "equals":
                          return p == a[0];
                        case "hashCode":
                          return System.identityHashCode(p);
                        default:
                          return zero(m.getReturnType());
                      }
                    });
      }
    }
  }

  private static void boards() {
    Board board = new Board();
    SidebarView sidebar = new SidebarView();
    check(
        sidebar.render(board.api, "Title", Arrays.asList("", "Same", "Same", "")),
        "sidebar render");
    check(
        board.objectives.get("tp_sidebar").scores.size() == 4 && board.teams.size() == 4,
        "blank and duplicate lines retained");
    check(
        board.teams.get("tpsb0").prefix.equals("")
            && board.teams.get("tpsb1").prefix.equals("Same"),
        "line order");
    sidebar.render(board.api, "Changed", Arrays.asList("New"));
    check(
        board.teams.size() == 1 && board.objectives.get("tp_sidebar").scores.size() == 1,
        "removed rows clear only owned entries");
    check(board.objectives.get("tp_sidebar").title.equals("Changed"), "title update");
    Objective foreign = board.api.registerNewObjective("foreign", "dummy", "Other");
    foreign.setDisplaySlot(DisplaySlot.SIDEBAR);
    check(
        !sidebar.render(board.api, "TP", Arrays.asList("Don't overwrite")),
        "foreign sidebar not replaced");
    check(
        board.active == foreign && !board.objectives.containsKey("tp_sidebar"),
        "owned sidebar yielded to foreign owner");
    sidebar.close();
    check(
        board.active == foreign && board.objectives.containsKey("foreign"),
        "close preserves foreign objective");
    Board names = new Board();
    GameProfile gp = new GameProfile(UUID.randomUUID(), "Alex");
    Player player = player("Alex", gp.getId(), new EntityPlayer(gp));
    NameTagView tags = new NameTagView(null);
    tags.apply(names.api, player, "§6[VIP] ", " §a✓", "ALWAYS", "NEVER");
    Board.T team = names.teams.values().iterator().next();
    check(
        team.prefix.equals("§6[VIP] ") && team.suffix.equals(" §a✓"), "name tag prefix and suffix");
    check(
        team.options.get(Team.Option.COLLISION_RULE) == Team.OptionStatus.NEVER,
        "collision option applied");
    check(team.entries.contains("Alex"), "real player assigned to own tag team");
    tags.close();
    check(names.teams.isEmpty(), "name tag close cleans owned teams");
    Team foreignTeam = names.api.registerNewTeam("other_plugin");
    foreignTeam.addEntry("Alex");
    tags.apply(names.api, player, "Override");
    check(
        names.teams.size() == 1 && names.teams.get("other_plugin").prefix.equals(""),
        "foreign player team not overwritten");
    tags.close();
    check(names.teams.containsKey("other_plugin"), "foreign team survives shutdown");
    Board sorted = new Board();
    Player second =
        player(
            "Zed", UUID.randomUUID(), new EntityPlayer(new GameProfile(UUID.randomUUID(), "Zed")));
    check(
        tags.apply(sorted.api, player, "", "", "ALWAYS", "ALWAYS", 12),
        "sorting without name tags creates an owned team");
    check(
        tags.apply(sorted.api, second, "", "", "ALWAYS", "ALWAYS", 2),
        "second sorted team created");
    String firstTeam = sorted.api.getEntryTeam("Alex").getName(),
        secondTeam = sorted.api.getEntryTeam("Zed").getName();
    check(
        firstTeam.length() == 16 && secondTeam.compareTo(firstTeam) < 0,
        "zero-padded team names enforce client order within 16 characters");
    tags.apply(sorted.api, player, "§6[VIP] ", " ★", "ALWAYS", "NEVER", 1);
    check(
        !sorted.teams.containsKey(firstTeam)
            && sorted.api.getEntryTeam("Alex").getName().compareTo(secondTeam) < 0,
        "changed priority replaces only the old owned team");
    Team blocker = sorted.api.registerNewTeam("foreign");
    blocker.addEntry("Alex");
    check(
        !tags.apply(sorted.api, player, "Bad", "", "ALWAYS", "ALWAYS", 0),
        "sorting refuses to steal a foreign player team");
    tags.close();
    check(
        sorted.teams.size() == 1 && blocker.hasEntry("Alex"),
        "sorting cleanup preserves the foreign team and entry");
  }

  private static final class TestPlugin extends JavaPlugin {
    TestPlugin(Server server, Path root) {
      super(
          new JavaPluginLoader(server),
          new PluginDescriptionFile("TestTabPrefix", "2.2.0", TestPlugin.class.getName()),
          root.toFile(),
          root.toFile());
    }
  }

  private static final class RuntimePlayer {
    final UUID id = UUID.randomUUID();
    final String name;
    final EntityPlayer entity;
    Scoreboard board;
    String header = "Original header", footer = "Original footer", listName, action = "";
    final Player.Spigot spigot =
        new Player.Spigot() {
          @Override
          public void sendMessage(ChatMessageType type, BaseComponent... parts) {
            if (type == ChatMessageType.ACTION_BAR) action = BaseComponent.toLegacyText(parts);
          }
        };
    final Player api;
    final Set<UUID> hidden = new HashSet<>();

    RuntimePlayer(String name, Scoreboard board) {
      this.name = name;
      this.listName = name;
      this.board = board;
      entity = new EntityPlayer(new GameProfile(id, name));
      World world =
          (World)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {World.class},
                  (p, m, a) -> m.getName().equals("getName") ? "world" : zero(m.getReturnType()));
      api =
          (Player)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {CraftPlayer.class},
                  (p, m, a) -> {
                    switch (m.getName()) {
                      case "spigot":
                        return spigot;
                      case "getUniqueId":
                        return id;
                      case "getName":
                        return name;
                      case "getPlayerListName":
                        return listName;
                      case "setPlayerListName":
                        listName = (String) a[0];
                        return null;
                      case "getHandle":
                        return entity;
                      case "getPing":
                        return 42;
                      case "getScoreboard":
                        return this.board;
                      case "setScoreboard":
                        this.board = (Scoreboard) a[0];
                        return null;
                      case "canSee":
                        return !hidden.contains(((Player) a[0]).getUniqueId());
                      case "getPlayerListHeader":
                        return header;
                      case "getPlayerListFooter":
                        return footer;
                      case "setPlayerListHeader":
                        header = (String) a[0];
                        return null;
                      case "setPlayerListFooter":
                        footer = (String) a[0];
                        return null;
                      case "getWorld":
                        return world;
                      case "getLocation":
                        return new Location(world, 1, 64, -1);
                      case "getHealth":
                        return 20d;
                      case "getMaxHealth":
                        return 20d;
                      case "getFoodLevel":
                        return 18;
                      case "getExp":
                        return .25f;
                      case "hasPermission":
                        return true;
                      case "getLevel":
                        return 5;
                      case "isOnline":
                        return true;
                      case "equals":
                        return p == a[0];
                      case "hashCode":
                        return System.identityHashCode(p);
                      default:
                        return zero(m.getReturnType());
                    }
                  });
    }
  }

  private static void runtime(
      Path root,
      PluginSettings settings,
      DisplayService service,
      PrefixService texts,
      GraphicService graphics)
      throws Exception {
    Board main = new Board();
    List<Board> allocated = new ArrayList<>();
    List<Runnable> tasks = new ArrayList<>();
    List<BossBar> nativeBars = new ArrayList<>();
    RuntimePlayer alice = new RuntimePlayer("Alice", main.api),
        bob = new RuntimePlayer("Bob", main.api);
    List<Player> players = Arrays.asList(alice.api, bob.api);
    ScoreboardManager manager =
        (ScoreboardManager)
            Proxy.newProxyInstance(
                DisplayIntegrationTest.class.getClassLoader(),
                new Class<?>[] {ScoreboardManager.class},
                (p, m, a) -> {
                  if (m.getName().equals("getMainScoreboard")) return main.api;
                  if (m.getName().equals("getNewScoreboard")) {
                    Board board = new Board();
                    allocated.add(board);
                    return board.api;
                  }
                  return zero(m.getReturnType());
                });
    BukkitTask task =
        (BukkitTask)
            Proxy.newProxyInstance(
                DisplayIntegrationTest.class.getClassLoader(),
                new Class<?>[] {BukkitTask.class},
                (p, m, a) -> zero(m.getReturnType()));
    BukkitScheduler scheduler =
        (BukkitScheduler)
            Proxy.newProxyInstance(
                DisplayIntegrationTest.class.getClassLoader(),
                new Class<?>[] {BukkitScheduler.class},
                (p, m, a) -> {
                  if (m.getName().equals("runTaskTimer")) {
                    tasks.add((Runnable) a[1]);
                    return task;
                  }
                  return zero(m.getReturnType());
                });
    PluginManager plugins =
        (PluginManager)
            Proxy.newProxyInstance(
                DisplayIntegrationTest.class.getClassLoader(),
                new Class<?>[] {PluginManager.class},
                (p, m, a) -> zero(m.getReturnType()));
    Server server =
        (Server)
            Proxy.newProxyInstance(
                DisplayIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (p, m, a) -> {
                  switch (m.getName()) {
                    case "getLogger":
                      return LOG;
                    case "getOnlinePlayers":
                      return players;
                    case "getScoreboardManager":
                      return manager;
                    case "getScheduler":
                      return scheduler;
                    case "getPluginManager":
                      return plugins;
                    case "getMaxPlayers":
                      return 100;
                    case "getName":
                      return "Fixture";
                    case "createBossBar":
                      BossBar bar =
                          new BossBarIntegrationTest.NativeBar(
                                  (String) a[0],
                                  (org.bukkit.boss.BarColor) a[1],
                                  (org.bukkit.boss.BarStyle) a[2])
                              .api;
                      nativeBars.add(bar);
                      return bar;
                    default:
                      return zero(m.getReturnType());
                  }
                });
    TestPlugin plugin = new TestPlugin(server, root);
    PackBuilder runtimeBuilder =
        new PackBuilder(
            settings, new MediaStore(settings), settings.minecraft, "http://127.0.0.1:8999");
    runtimeBuilder.designs(() -> service.snapshot().design);
    ResourcePackService runtimePacks =
        new ResourcePackService(runtimeBuilder, graphics, (e, v) -> {});
    await(runtimePacks.initialize());
    MessageService runtimeMessages =
        new MessageService(
            LOG,
            new ConfigurationSnapshot(
                settings,
                new HashMap<String, String>() {
                  {
                    put("resource-pack.accepted", "accepted");
                    put("resource-pack.loaded", "loaded");
                  }
                }));
    PackDelivery runtimeDelivery =
        new PackDelivery(plugin, runtimePacks, runtimeMessages, (e, v) -> {}, settings);
    PrefixController prefixes =
        new PrefixController(
            plugin, texts, graphics, null, runtimeDelivery, settings, new TabListView());
    Class<?> stateClass =
        Class.forName("me.snowsun.tabprefix.presentation.display.PrefixController$State");
    Constructor<?> constructor =
        stateClass.getDeclaredConstructor(String.class, AssetDescriptor.class, String.class);
    constructor.setAccessible(true);
    Field field = PrefixController.class.getDeclaredField("states");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<UUID, Object> states = (Map<UUID, Object>) field.get(prefixes);
    states.put(alice.id, constructor.newInstance("§6[VIP] ", null, "vip"));
    states.put(bob.id, constructor.newInstance("§a[Member] ", null, "default"));
    AssetDescriptor animated =
        new AssetDescriptor(
            UUID.randomUUID(), "gif", 1, 1, 1, 0, new int[] {2, 2}, new int[] {0xe000, 0xe001}, 1);
    Object original = states.get(alice.id);
    states.put(alice.id, constructor.newInstance("§6[VIP] ", animated, "vip"));
    check(
        prefixes.graphicalAnimationDue(settings.features.period),
        "GIF display pulses independent of slower text refresh");
    states.put(alice.id, original);
    check(
        !prefixes.graphicalAnimationDue(settings.features.period),
        "No extra GIF display pulses for static assets");
    DisplayController controller = new DisplayController(plugin, service, prefixes, settings);
    controller.start();
    JsonObject doc = service.snapshot().design.json();
    for (String k :
        Arrays.asList(
            "headerEnabled",
            "footerEnabled",
            "sidebarEnabled",
            "layoutEnabled",
            "nameTagsEnabled",
            "bossBarsEnabled")) doc.addProperty(k, true);
    JsonArray runtimeBars = new JsonArray();
    runtimeBars.add(sampleBoss());
    doc.add("bossBars", runtimeBars);
    await(service.save(new DisplayDesign(doc), service.snapshot().revision, alice.id));
    tasks.get(0).run();
    check(
        alice.header.contains("Alice") && bob.header.contains("Bob"),
        "production header placeholders personalized per recipient");
    check(
        alice.board != main.api && bob.board != main.api && alice.board != bob.board,
        "production allocates separate pristine boards");
    check(
        allocated.get(0).active != null && allocated.get(1).active != null,
        "production sidebar displayed for both recipients");
    check(
        alice.board.getEntryTeam("Bob").getPrefix().contains("Member"),
        "production name tags use actual cached group prefix");
    check(
        !alice.entity.playerConnection.packets.isEmpty(),
        "production controller emits layout packets");
    check(
        nativeBars.size() == 2
            && nativeBars.get(0).getTitle().contains("Alice")
            && nativeBars.get(1).getTitle().contains("Bob"),
        "production controller attaches personalized native boss bars");
    JsonObject preview = controller.preview(alice.api, new DisplayDesign(doc), 100);
    check(
        preview.get("online").getAsInt() == 2 && preview.getAsJsonArray("slots").size() == 80,
        "production preview uses visible players and renderer");
    check(
        preview.getAsJsonArray("bossBars").size() == 1
            && preview
                .getAsJsonArray("bossBars")
                .get(0)
                .getAsJsonObject()
                .get("active")
                .getAsBoolean(),
        "production preview includes active boss bars");
    JsonObject hud = new JsonParser().parse(doc.toString()).getAsJsonObject();
    hud.addProperty("screenEnabled", true);
    JsonObject block = new JsonObject(), hudLine = new JsonObject();
    block.addProperty("id", "runtime-hud");
    block.addProperty("anchor", "ACTION_BAR");
    hudLine.addProperty("text", "<green>{player} {health}/{max_health}</green>");
    block.add("line", hudLine);
    JsonArray screen = new JsonArray();
    screen.add(block);
    hud.add("screen", screen);
    JsonObject hudPreview = controller.preview(alice.api, new DisplayDesign(hud), 0);
    check(
        hudPreview.getAsJsonObject("screen").get("ACTION_BAR").toString().contains("Alice 20/20"),
        "production HUD placeholders resolved");
    JsonArray worlds = new JsonArray();
    worlds.add("nether");
    block.add("worlds", worlds);
    check(
        controller
            .preview(alice.api, new DisplayDesign(hud), 0)
            .getAsJsonArray("screenElements")
            .get(0)
            .getAsJsonObject()
            .get("reason")
            .getAsString()
            .equals("WORLD"),
        "production HUD world restriction");
    worlds.remove(0);
    block.addProperty("enabled", false);
    check(
        controller
            .preview(alice.api, new DisplayDesign(hud), 0)
            .getAsJsonArray("screenElements")
            .get(0)
            .getAsJsonObject()
            .get("reason")
            .getAsString()
            .equals("DISABLED"),
        "production HUD block opt-out");
    block.addProperty("enabled", true);
    JsonObject hudPrefs = service.preferences(alice.id).json();
    hudPrefs.addProperty("screen", false);
    await(service.savePreferences(alice.id, new PlayerDisplaySettings(hudPrefs)));
    check(
        controller
            .preview(alice.api, new DisplayDesign(hud), 0)
            .getAsJsonArray("screenElements")
            .get(0)
            .getAsJsonObject()
            .get("reason")
            .getAsString()
            .equals("PERSONAL_DISABLED"),
        "production HUD personal opt-out");
    hudPrefs.addProperty("screen", true);
    await(service.savePreferences(alice.id, new PlayerDisplaySettings(hudPrefs)));
    hud.addProperty("screenEnabled", false);
    check(
        controller
            .preview(alice.api, new DisplayDesign(hud), 0)
            .getAsJsonArray("screenElements")
            .get(0)
            .getAsJsonObject()
            .get("reason")
            .getAsString()
            .equals("SERVER_DISABLED"),
        "production HUD server opt-out");
    JsonObject pulsing = new JsonParser().parse(doc.toString()).getAsJsonObject();
    pulsing.addProperty("refreshTicks", 200);
    JsonObject pulsingBar = pulsing.getAsJsonArray("bossBars").get(0).getAsJsonObject();
    pulsingBar.addProperty("progressMode", "DRAIN");
    pulsingBar.addProperty("durationTicks", 40);
    await(service.save(new DisplayDesign(pulsing), service.snapshot().revision, alice.id));
    tasks.get(0).run();
    for (int pulse = 0; pulse < 20; pulse++) tasks.get(0).run();
    check(
        nativeBars.get(0).getProgress() == .5,
        "Boss timers pulse independently of a slow normal display interval");
    JsonObject nativeDoc = new JsonParser().parse(doc.toString()).getAsJsonObject();
    nativeDoc.addProperty("layoutEnabled", false);
    nativeDoc.addProperty("sidebarMode", "HUD");
    nativeDoc.addProperty("title", "PANEL {player}");
    nativeDoc.addProperty("screenEnabled", true);
    JsonObject freeBlock = new JsonObject();
    freeBlock.addProperty("id", "runtime-free");
    freeBlock.addProperty("kind", "TEXT");
    freeBlock.addProperty("anchor", "FREE_XY");
    freeBlock.addProperty("x", -40);
    freeBlock.addProperty("y", -70);
    freeBlock.addProperty("height", 8);
    JsonObject blockLine = new JsonObject();
    blockLine.addProperty("text", "HUD {player}");
    freeBlock.add("line", blockLine);
    JsonArray nativeBlocks = new JsonArray();
    nativeBlocks.add(freeBlock);
    nativeDoc.add("screen", nativeBlocks);
    DisplayDesign nativeDesign = new DisplayDesign(nativeDoc);
    await(service.save(nativeDesign, service.snapshot().revision, alice.id));
    tasks.get(0).run();
    check(
        alice.board.getObjective(DisplaySlot.SIDEBAR) == null,
        "scoreless mode does not create an objective or numeric scores");
    check(
        alice.footer.contains("PANEL Alice")
            && controller
                .preview(alice.api, nativeDesign, 0)
                .get("sidebarPlacement")
                .getAsString()
                .equals("TAB_FOOTER"),
        "unloaded pack uses personalized TAB fallback");
    PackRevision canvasRevision = await(runtimePacks.rebuild());
    check(
        canvasRevision.glyphs == 0 && canvasRevision.hud.present(),
        "production canvas pack works without prefix images");
    check(
        runtimeDelivery.send(alice.api, canvasRevision) == PackRequestState.Offer.SEND,
        "canvas-only pack offered");
    runtimeDelivery.status(
        new PlayerResourcePackStatusEvent(
            alice.api, PlayerResourcePackStatusEvent.Status.ACCEPTED));
    check(!runtimeDelivery.canvas(alice.id).present(), "acceptance alone does not activate HUD");
    runtimeDelivery.status(
        new PlayerResourcePackStatusEvent(
            alice.api, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
    tasks.get(0).run();
    tasks.get(0).run();
    check(
        alice
            .action
            .codePoints()
            .anyMatch(c -> c >= NativeHudLayout.FIRST && c < NativeHudLayout.SPACE),
        "actual native action bar carries private canvas glyphs");
    check(
        !alice.footer.contains("PANEL Alice")
            && controller
                .preview(alice.api, nativeDesign, 0)
                .get("sidebarPlacement")
                .getAsString()
                .equals("FONT_CANVAS"),
        "loaded pack replaces TAB fallback with canvas");
    int blockBase = canvasRevision.hud.get("screen:runtime-free", -70, 8, "").base;
    JsonObject canvasPrefs = new JsonObject();
    canvasPrefs.addProperty("screen", false);
    await(service.savePreferences(alice.id, new PlayerDisplaySettings(canvasPrefs)));
    tasks.get(0).run();
    tasks.get(0).run();
    check(
        alice
            .action
            .codePoints()
            .noneMatch(c -> c >= blockBase && c < blockBase + NativeHudLayout.STRIDE),
        "personal screen opt-out removes free blocks");
    check(
        alice
            .action
            .codePoints()
            .anyMatch(c -> c >= NativeHudLayout.FIRST && c < NativeHudLayout.SPACE),
        "screen opt-out preserves independently enabled scoreless panel");
    await(service.savePreferences(alice.id, new PlayerDisplaySettings(new JsonObject())));
    nativeDoc.addProperty("sidebarY", nativeDesign.sidebarY + 1);
    DisplayDesign movedCanvas = new DisplayDesign(nativeDoc);
    await(service.save(movedCanvas, service.snapshot().revision, alice.id));
    tasks.get(0).run();
    check(
        alice.footer.contains("PANEL Alice"),
        "changed Y uses text fallback until matching revision loads");
    JsonObject styled = new JsonParser().parse(doc.toString()).getAsJsonObject();
    for (String key :
        Arrays.asList(
            "sidebarEnabled", "headerEnabled", "footerEnabled", "layoutEnabled", "nameTagsEnabled"))
      styled.addProperty(key, false);
    styled.addProperty("tabFormatEnabled", true);
    styled.addProperty("nativeSortEnabled", true);
    styled.addProperty("sort", "PRIORITY");
    styled.add("groupStyles", sampleStyles());
    await(service.save(new DisplayDesign(styled), service.snapshot().revision, alice.id));
    tasks.get(0).run();
    check(
        alice
                .board
                .getEntryTeam("Bob")
                .getName()
                .compareTo(alice.board.getEntryTeam("Alice").getName())
            < 0,
        "production normal TAB follows manual group priority");
    check(
        alice.board.getEntryTeam("Bob").getPrefix().isEmpty()
            && alice.board.getEntryTeam("Bob").getSuffix().isEmpty(),
        "sort-only teams leave name tags undecorated");
    preview = controller.preview(alice.api, new DisplayDesign(styled), 0);
    check(
        preview.getAsJsonArray("players").get(0).toString().contains("Bob")
            && preview.getAsJsonArray("players").get(1).toString().contains("STAR vip"),
        "production preview orders players and resolves group suffixes");
    PacketPlayOutPlayerInfo normalPacket = lastNames(alice.entity);
    check(
        normalPacket.entries.size() == 2
            && normalPacket.entries.get(0).display.json.contains("MEMBER")
            && normalPacket.entries.get(1).display.json.contains("STAR vip"),
        "production ordinary TAB emits formatted per-recipient names");
    int beforePackets = alice.entity.playerConnection.packets.size();
    for (int i = 0; i < 8; i++) tasks.get(0).run();
    check(
        alice.entity.playerConnection.packets.size() == beforePackets,
        "unchanged ordinary player names do not emit duplicate packets");
    JsonObject ownPrefs = new JsonObject();
    ownPrefs.addProperty("suffixes", false);
    ownPrefs.addProperty("sorting", false);
    await(service.savePreferences(alice.id, new PlayerDisplaySettings(ownPrefs)));
    tasks.get(0).run();
    check(
        alice.board == main.api && bob.board != main.api,
        "sorting opt-out restores only the recipient's owned board");
    check(
        !lastNames(alice.entity).entries.get(0).display.json.contains("STAR")
            && lastNames(bob.entity).entries.stream()
                .anyMatch(entry -> entry.display.json.contains("STAR")),
        "suffix opt-out changes only the recipient's native names");
    await(service.savePreferences(alice.id, new PlayerDisplaySettings(new JsonObject())));
    styled.addProperty("nameTagsEnabled", true);
    styled.addProperty("nameTagSuffix", "{suffix}");
    await(service.save(new DisplayDesign(styled), service.snapshot().revision, alice.id));
    tasks.get(0).run();
    check(
        alice.board.getEntryTeam("Alice").getSuffix().contains("STAR vip"),
        "group suffix placeholder works above the real player's head");
    JsonObject animatedTab = new JsonParser().parse(styled.toString()).getAsJsonObject();
    animatedTab.addProperty("refreshTicks", 200);
    JsonObject animatedSuffix =
        animatedTab
            .getAsJsonArray("groupStyles")
            .get(1)
            .getAsJsonObject()
            .getAsJsonObject("suffix");
    animatedSuffix.addProperty("animation", "FRAMES");
    animatedSuffix.addProperty("speed", 2);
    JsonArray suffixFrames = new JsonArray();
    suffixFrames.add("ONE");
    suffixFrames.add("TWO");
    animatedSuffix.add("frames", suffixFrames);
    await(service.save(new DisplayDesign(animatedTab), service.snapshot().revision, alice.id));
    tasks.get(0).run();
    int animatedBefore = alice.entity.playerConnection.packets.size();
    for (int i = 0; i < 6; i++) tasks.get(0).run();
    check(
        alice.entity.playerConnection.packets.size() > animatedBefore,
        "suffix animations pulse independently of a slow normal refresh interval");
    await(service.save(new DisplayDesign(doc), service.snapshot().revision, alice.id));
    tasks.get(0).run();
    alice.hidden.add(bob.id);
    preview = controller.preview(alice.api, new DisplayDesign(doc), 100);
    check(preview.get("online").getAsInt() == 1, "vanished players excluded from preview count");
    JsonObject pref = new JsonObject();
    for (String k :
        Arrays.asList("sidebar", "headerFooter", "layout", "nameTags", "animations", "bossBars"))
      pref.addProperty(k, false);
    await(service.savePreferences(alice.id, new PlayerDisplaySettings(pref)));
    tasks.get(0).run();
    check(
        alice.board == main.api && bob.board != main.api,
        "one player's options do not change another player's board");
    check(
        alice.header.equals("Original header") && alice.footer.equals("Original footer"),
        "headers restored when player disables them");
    check(
        allocated.get(0).objectives.isEmpty() && allocated.get(0).teams.isEmpty(),
        "disabled recipient's owned display resources cleaned");
    check(
        nativeBars.get(0).getPlayers().isEmpty()
            && nativeBars.get(1).getPlayers().contains(bob.api),
        "production personal boss-bar opt-out preserves another recipient");
    check(
        ((PacketPlayOutPlayerInfo)
                    alice.entity.playerConnection.packets.get(
                        alice.entity.playerConnection.packets.size() - 1))
                .action
            == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER,
        "disabling layout removes synthetic slots");
    bob.header = "Foreign header";
    Board external = new Board();
    Objective foreign = external.api.registerNewObjective("external", "dummy", "Other");
    foreign.setDisplaySlot(DisplaySlot.SIDEBAR);
    bob.board = external.api;
    await(service.savePreferences(bob.id, new PlayerDisplaySettings(new JsonObject())));
    tasks.get(0).run();
    check(
        bob.board == external.api && external.active == foreign,
        "production respects another plugin's board and sidebar");
    check(
        bob.header.equals("Foreign header"),
        "production yields header ownership to another plugin");
    JsonObject onlyFooter = service.snapshot().design.json();
    onlyFooter.addProperty("headerEnabled", false);
    await(service.save(new DisplayDesign(onlyFooter), service.snapshot().revision, bob.id));
    tasks.get(0).run();
    check(
        bob.header.equals("Foreign header") && !bob.footer.equals("Original footer"),
        "footer ownership does not overwrite foreign header");
    controller.close();
    runtimeDelivery.close();
    runtimePacks.close();
    runtimeMessages.close();
    check(
        bob.header.equals("Foreign header") && external.active == foreign,
        "shutdown preserves external state");
    check(
        allocated.get(1).objectives.isEmpty() && allocated.get(1).teams.isEmpty(),
        "detached owned board cleaned");
    check(
        nativeBars.stream().allMatch(bar -> bar.getPlayers().isEmpty()),
        "production shutdown removes all owned boss bars");
  }

  private static PacketPlayOutPlayerInfo lastNames(EntityPlayer player) {
    List<Packet> packets = player.playerConnection.packets;
    for (int i = packets.size() - 1; i >= 0; i--) {
      PacketPlayOutPlayerInfo packet = (PacketPlayOutPlayerInfo) packets.get(i);
      if (packet.action == PacketPlayOutPlayerInfo.EnumPlayerInfoAction.UPDATE_DISPLAY_NAME)
        return packet;
    }
    throw new AssertionError("No display-name packet");
  }

  private static JsonObject sampleBoss() {
    JsonObject bar = new JsonObject();
    bar.addProperty("id", "welcome");
    bar.addProperty("progress", 50);
    JsonObject line = new JsonObject();
    line.addProperty("text", "<aqua>{player}</aqua> · {progress}%");
    bar.add("line", line);
    return bar;
  }

  private static JsonArray sampleGroups() {
    JsonArray groups = new JsonArray();
    String[] names = {"owner", "admin", "vip", "default"};
    int[] weights = {100, 80, 20, 0};
    for (int i = 0; i < names.length; i++) {
      JsonObject group = new JsonObject();
      group.addProperty("group", names[i]);
      group.addProperty("weight", weights[i]);
      groups.add(group);
    }
    return groups;
  }

  private static JsonArray sampleStyles() {
    JsonArray styles = new JsonArray();
    for (String group : Arrays.asList("default", "vip")) {
      JsonObject role = new JsonObject(), suffix = new JsonObject();
      role.addProperty("group", group);
      role.addProperty("nameColor", "#93efc4");
      role.addProperty("suffixMode", "CUSTOM");
      suffix.addProperty(
          "text", group.equals("vip") ? "<gold>STAR {group}</gold>" : "<aqua>MEMBER</aqua>");
      role.add("suffix", suffix);
      styles.add(role);
    }
    return styles;
  }

  private static JsonObject preview(
      TextRenderer renderer, DisplayDesign d, long tick, PlayerDisplaySettings prefs) {
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("player", "SnowySun");
    vars.put("server", d.serverName);
    vars.put("online", "2");
    vars.put("max", "100");
    vars.put("world", "world");
    vars.put("ping", "42");
    vars.put("time", "12:34");
    vars.put("date", "2026-10-10");
    vars.put("group", "vip");
    vars.put("prefix", "§6[VIP] ");
    vars.put("health", "20");
    vars.put("max_health", "20");
    vars.put("food", "18");
    vars.put("experience", "25");
    vars.put("level", "5");
    vars.put("weight", "20");
    vars.put("suffix", "");
    vars.put("tab_name", "");
    vars.put("display_name", "SnowySun");
    vars.put(
        "suffix",
        TabFormatting.suffix(
            d,
            d.groupStyle("vip"),
            "§bLP",
            vars,
            renderer,
            tick,
            prefs.suffixes,
            prefs.animations));
    vars.put("display_name", TabFormatting.name("SnowySun", d.groupStyle("vip"), renderer));
    vars.put(
        "tab_name", TabFormatting.line(d.tabPlayerFormat, vars, renderer, tick, prefs.animations));
    JsonObject j = new JsonObject();
    for (String key : Arrays.asList("header", "footer", "sidebar")) {
      JsonArray a = new JsonArray();
      java.util.List<DisplayDesign.Line> lines =
          key.equals("header") ? d.header : key.equals("footer") ? d.footer : d.sidebar;
      for (DisplayDesign.Line l : lines) {
        String legacy = renderer.legacy(renderer.display(l.source(tick, prefs.animations), vars));
        if (prefs.animations && l.animation.equals("SCROLL"))
          legacy = DisplayAnimation.scroll(legacy, l.width, l.gap, tick / l.speed);
        a.add(rich(legacy));
      }
      j.add(key, a);
    }
    j.add("title", rich(renderer.legacy(renderer.display(d.title, vars))));
    j.add(
        "nameTag",
        rich(
            renderer.legacy(renderer.display(d.nameTagPrefix, vars))
                + "§rSnowySun"
                + renderer.legacy(renderer.display(d.nameTagSuffix, vars))));
    JsonArray slots = new JsonArray();
    for (DisplayDesign.Slot s : d.slots) {
      String text =
          s.kind.equals("EMPTY") || s.kind.equals("PLAYER") && s.playerIndex > 2
              ? ""
              : renderer.legacy(renderer.display(s.line.source(tick, prefs.animations), vars));
      if (prefs.animations && s.line.animation.equals("SCROLL"))
        text = DisplayAnimation.scroll(text, s.line.width, s.line.gap, tick / s.line.speed);
      slots.add(rich(text));
    }
    j.add("slots", slots);
    JsonArray players = new JsonArray();
    players.add(rich(d.tabFormatEnabled ? vars.get("tab_name") : "§6[VIP] SnowySun"));
    players.add(rich("Alex"));
    j.add("players", players);
    j.addProperty("online", 2);
    j.addProperty("overflow", 0);
    j.addProperty("layoutAvailable", true);
    j.addProperty("sidebarConflicts", 0);
    j.addProperty("nameTagConflicts", 0);
    j.addProperty("sortConflicts", 0);
    j.add("playerFormat", rich(vars.get("tab_name")));
    JsonArray styles = new JsonArray();
    for (DisplayDesign.GroupStyle style : d.groupStyles) {
      Map<String, String> sample = new LinkedHashMap<>(vars);
      sample.put("group", style.group);
      sample.put("suffix", "");
      sample.put("tab_name", "");
      JsonObject row = new JsonObject();
      row.addProperty("group", style.group);
      row.add(
          "suffix",
          rich(
              TabFormatting.suffix(
                  d,
                  style,
                  style.group.equals("vip") ? "§bLP" : "",
                  sample,
                  renderer,
                  tick,
                  prefs.suffixes,
                  prefs.animations)));
      styles.add(row);
    }
    j.add("groupStyles", styles);
    JsonArray bars = new JsonArray();
    Player viewer = new RuntimePlayer("SnowySun", null).api;
    for (BossBarView.Frame frame :
        BossBarView.frames(
            viewer,
            d,
            prefs,
            vars,
            tick,
            (l, v) -> {
              String result =
                  renderer.legacy(renderer.display(l.source(tick, prefs.animations), v));
              return prefs.animations && l.animation.equals("SCROLL")
                  ? DisplayAnimation.scroll(result, l.width, l.gap, tick / l.speed)
                  : result;
            })) {
      JsonObject bar = new JsonObject();
      bar.addProperty("id", frame.design.id);
      bar.add("title", rich(frame.title));
      bar.addProperty("progress", frame.progress);
      bar.addProperty("color", frame.design.color);
      bar.addProperty("style", frame.design.style);
      bar.addProperty("active", frame.active);
      bar.addProperty("reason", frame.reason);
      bars.add(bar);
    }
    j.add("bossBars", bars);
    JsonArray elements = new JsonArray();
    for (DisplayDesign.Screen item : d.screen) {
      JsonObject row = new JsonObject();
      row.addProperty("id", item.id);
      row.addProperty(
          "reason",
          !d.screenEnabled
              ? "SERVER_DISABLED"
              : !prefs.screen
                  ? "PERSONAL_DISABLED"
                  : !item.enabled
                      ? "DISABLED"
                      : !item.worlds.isEmpty() && !item.worlds.contains("world") ? "WORLD" : "");
      String result =
          renderer.legacy(renderer.display(item.line.source(tick, prefs.animations), vars));
      if (prefs.animations && item.line.animation.equals("SCROLL"))
        result =
            DisplayAnimation.scroll(result, item.line.width, item.line.gap, tick / item.line.speed);
      row.add("text", rich(result));
      elements.add(row);
    }
    j.add("screenElements", elements);
    return j;
  }

  private static JsonElement rich(String legacy) {
    return new JsonParser()
        .parse(
            net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                .serialize(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacySection()
                        .deserialize(legacy)));
  }

  private static final class Reply {
    final int code;
    final String text;

    Reply(int code, String text) {
      this.code = code;
      this.text = text;
    }

    JsonObject json() {
      return new JsonParser().parse(text).getAsJsonObject();
    }
  }

  private static Reply request(
      String base, String token, String path, JsonObject body, String origin) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(base + "/api/" + path).openConnection();
    c.setRequestProperty("Authorization", "Bearer " + token);
    c.setConnectTimeout(5000);
    c.setReadTimeout(5000);
    if (body != null) {
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      if (origin != null) c.setRequestProperty("Origin", origin);
      try (OutputStream out = c.getOutputStream()) {
        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
      }
    }
    int code = c.getResponseCode();
    String text;
    try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
      text = new String(read(in), StandardCharsets.UTF_8);
    } finally {
      c.disconnect();
    }
    return new Reply(code, text);
  }

  private static byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    for (int n; (n = in.read(b)) != -1; ) out.write(b, 0, n);
    return out.toByteArray();
  }

  private static void http(EditorServer editor, DisplayService service, AtomicBoolean allowed)
      throws Exception {
    UUID owner = UUID.randomUUID();
    String url = editor.openStudio(owner, "Admin", null, true, true),
        base = url.substring(0, url.indexOf("/studio#")),
        token = url.substring(url.indexOf('#') + 1);
    check(
        request(base, token, "session", null, null).json().get("canDesign").getAsBoolean(),
        "design capability exposed");
    check(
        request(base, token, "session", null, null).json().get("canPrefixes").getAsBoolean()
            == false,
        "studio session has no prefix write capability");
    check(
        request(base, token, "save", new JsonObject(), null).code == 403,
        "studio cannot edit group prefixes");
    Reply get = request(base, token, "display/design", null, null);
    check(get.code == 200, "authenticated design load");
    check(
        request(base, token, "display/groups", null, null).code == 200
            && new JsonParser()
                    .parse(request(base, token, "display/groups", null, null).text)
                    .getAsJsonArray()
                    .size()
                == 4,
        "designer can read live group metadata");
    check(
        request(base, token, "display/groups", new JsonObject(), null).code == 405,
        "group metadata endpoint is read-only");
    JsonObject payload = get.json();
    payload.getAsJsonObject("design").addProperty("headerEnabled", true);
    payload.getAsJsonObject("design").addProperty("bossBarsEnabled", true);
    payload.getAsJsonObject("design").addProperty("tabFormatEnabled", true);
    payload.getAsJsonObject("design").addProperty("nativeSortEnabled", true);
    payload.getAsJsonObject("design").add("groupStyles", sampleStyles());
    JsonArray apiBars = new JsonArray();
    apiBars.add(sampleBoss());
    payload.getAsJsonObject("design").add("bossBars", apiBars);
    Reply saved = request(base, token, "display/design", payload, null);
    check(saved.code == 200, "authenticated save");
    check(
        saved.json().getAsJsonObject("design").getAsJsonArray("bossBars").size() == 1,
        "authenticated boss bar save round trip");
    check(
        request(base, token, "display/design", payload, null).code == 409,
        "HTTP optimistic conflict");
    JsonObject preview = new JsonObject();
    preview.add("design", service.snapshot().design.json());
    preview.addProperty("tick", 100);
    check(
        request(base, token, "display/preview", preview, null).json().getAsJsonArray("slots").size()
            == 80,
        "real API preview components");
    JsonObject pref = new JsonObject();
    pref.addProperty("sidebar", false);
    pref.addProperty("bossBars", false);
    pref.addProperty("suffixes", false);
    pref.addProperty("sorting", false);
    check(request(base, token, "preferences", pref, null).code == 200, "save personal settings");
    check(!service.preferences(owner).sidebar, "HTTP preferences bound to session owner");
    check(!service.preferences(owner).bossBars, "HTTP boss-bar preference bound to session owner");
    check(
        !service.preferences(owner).suffixes && !service.preferences(owner).sorting,
        "HTTP suffix and sorting preferences bound to session owner");
    check(
        request(base, token, "preferences", pref, "https://attacker.invalid").code == 403,
        "foreign origin blocked");
    check(request(base, "bad", "preferences", null, null).code == 401, "bad token blocked");
    allowed.set(false);
    check(
        request(base, token, "display/design", null, null).code == 403,
        "revoked permissions immediately denied");
    check(
        request(base, token, "preferences", pref, null).code == 403,
        "offline owner cannot save preferences");
    allowed.set(true);
    String player = editor.openStudio(UUID.randomUUID(), "Player", null, false, true),
        playerToken = player.substring(player.indexOf('#') + 1);
    check(
        request(base, playerToken, "display/design", null, null).code == 403,
        "player cannot read server design");
    check(
        request(base, playerToken, "display/groups", null, null).code == 403,
        "personal-only session cannot read group metadata");
    check(
        request(base, playerToken, "display/preview", preview, null).code == 403,
        "player cannot preview arbitrary designs");
    check(
        request(base, playerToken, "preferences", null, null).code == 200,
        "player can load own preferences");
    JsonObject invalid = service.snapshot().json();
    invalid.getAsJsonObject("design").addProperty("refreshTicks", 0);
    check(
        request(base, token, "display/design", invalid, null).code == 400,
        "invalid candidate refused before save");
    check(service.snapshot().design.headerEnabled, "bad candidate preserves active state");
    JsonObject newline = service.snapshot().json();
    newline.getAsJsonObject("design").addProperty("title", "<newline>");
    check(
        request(base, token, "display/design", newline, null).code == 400,
        "rendered newlines refused before saving");
    JsonObject bossNewline = service.snapshot().json();
    bossNewline
        .getAsJsonObject("design")
        .getAsJsonArray("bossBars")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("line")
        .addProperty("text", "<newline>");
    check(
        request(base, token, "display/design", bossNewline, null).code == 400,
        "Rendered boss-bar newline refused before saving");
    JsonObject suffixNewline = service.snapshot().json();
    suffixNewline
        .getAsJsonObject("design")
        .getAsJsonArray("groupStyles")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("suffix")
        .addProperty("text", "<newline>");
    check(
        request(base, token, "display/design", suffixNewline, null).code == 400,
        "rendered suffix newline rejected before save");
    JsonObject formatNewline = service.snapshot().json();
    formatNewline
        .getAsJsonObject("design")
        .getAsJsonObject("tabPlayerFormat")
        .addProperty("text", "<newline>");
    check(
        request(base, token, "display/design", formatNewline, null).code == 400,
        "rendered TAB format newline rejected before save");
    for (int i = 0; i < 35; i++)
      check(
          request(base, token, "display/preview", preview, null).code == 200,
          "preview does not consume mutation allowance " + i);
  }
}
