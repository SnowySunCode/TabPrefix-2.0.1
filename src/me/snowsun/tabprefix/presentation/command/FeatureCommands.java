package me.snowsun.tabprefix.presentation.command;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.application.port.*;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.audit.AuditService;
import me.snowsun.tabprefix.infrastructure.luckperms.LuckPermsBridge;
import me.snowsun.tabprefix.infrastructure.resourcepack.ResourcePackService;
import me.snowsun.tabprefix.infrastructure.web.EditorServer;
import me.snowsun.tabprefix.presentation.display.PackDelivery;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.util.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class FeatureCommands implements ExtraCommands {
  private final JavaPlugin plugin;
  private final CoreControl core;
  private final GraphicService graphics;
  private final PrefixService texts;
  private final LuckPermsBridge luck;
  private final EditorServer editor;
  private final ResourcePackService packs;
  private final PackDelivery delivery;
  private final AuditService audit;
  private final MessageService messages;
  private final MainThreadExecutor main;
  private final Supplier<PluginSettings> settings;

  public FeatureCommands(
      JavaPlugin plugin,
      CoreControl core,
      GraphicService graphics,
      PrefixService texts,
      LuckPermsBridge luck,
      EditorServer editor,
      ResourcePackService packs,
      PackDelivery delivery,
      AuditService audit,
      MessageService messages,
      MainThreadExecutor main,
      Supplier<PluginSettings> settings) {
    this.plugin = plugin;
    this.core = core;
    this.graphics = graphics;
    this.texts = texts;
    this.luck = luck;
    this.editor = editor;
    this.packs = packs;
    this.delivery = delivery;
    this.audit = audit;
    this.messages = messages;
    this.main = main;
    this.settings = settings;
  }

  private boolean allowed(CommandSender s, String permission) {
    if (s.hasPermission(permission)) return true;
    messages.send(s, "general.no-permission");
    return false;
  }

  private void usage(CommandSender s) {
    messages.send(s, "general.invalid-arguments");
  }

  private Player player(CommandSender s) {
    if (s instanceof Player) return (Player) s;
    messages.send(s, "general.players-only");
    return null;
  }

  private String group(CommandSender sender, String requested) {
    if (requested != null) {
      if (!allowed(sender, "tabprefix.prefix.manage")) return null;
      return GroupTextPrefix.normalizeGroup(requested);
    }
    Player p = player(sender);
    if (p == null) return null;
    PlayerIdentity identity = luck.identity(p, settings.get().usePrimaryGroup);
    if (identity == null) {
      messages.send(sender, "luckperms.user-not-loaded");
      return null;
    }
    return identity.group;
  }

  @Override
  public boolean handle(CommandSender s, String action, String[] args) {
    if (!Arrays.asList("webeditor", "webpref", "pack", "glyphs", "adminlog").contains(action))
      return false;
    if (!core.ready()) {
      messages.send(s, "general.not-ready");
      return true;
    }
    switch (action) {
      case "webeditor":
        webEditor(s, args);
        break;
      case "webpref":
        webPrefix(s, args);
        break;
      case "pack":
        pack(s, args);
        break;
      case "glyphs":
        if (allowed(s, "tabprefix.pack.status")) glyphs(s);
        break;
      case "adminlog":
        admin(s, args);
        break;
      default:
        break;
    }
    return true;
  }

  private void webEditor(CommandSender s, String[] args) {
    if (!allowed(s, "tabprefix.webeditor")) return;
    if (args.length > 2) {
      usage(s);
      return;
    }
    Player p = player(s);
    if (p == null) return;
    String group = group(s, args.length == 2 ? args[1] : null);
    if (group == null) return;
    messages.send(s, "webeditor.starting");
    luck.groupExistsAsync(group)
        .whenComplete(
            (exists, error) ->
                main.execute(
                    () -> {
                      if (error != null) {
                        fail(s, "webeditor.session-failed", error);
                        return;
                      }
                      if (!exists) {
                        messages.send(s, "text-prefix.group-not-found", Values.of("group", group));
                        return;
                      }
                      try {
                        String url = editor.open(p.getUniqueId(), p.getName(), group);
                        messages.send(s, "webeditor.opened");
                        messages.link(
                            s,
                            "webeditor.link",
                            Values.of("player", p.getName(), "group", group),
                            url);
                        messages.send(
                            s,
                            "webeditor.expires",
                            Values.of("minutes", settings.get().features.sessionMinutes));
                      } catch (IllegalArgumentException e) {
                        messages.send(s, "webeditor.session-limit");
                      } catch (IllegalStateException e) {
                        messages.send(s, "webeditor.unavailable");
                      }
                    }));
  }

  private void webPrefix(CommandSender s, String[] args) {
    if (args.length < 2 || args.length > 3) {
      usage(s);
      return;
    }
    String action = args[1].toLowerCase(Locale.ROOT);
    if (action.equals("info") || action.equals("remove")) {
      if (!allowed(s, "tabprefix.webpref." + action)) return;
      String group = group(s, args.length == 3 ? args[2] : null);
      if (group == null) return;
      if (action.equals("info")) {
        info(s, group);
        return;
      }
      boolean existed =
          graphics.snapshot().prefixes.containsKey(group) || texts.find(group) != null;
      graphics
          .remove(group)
          .whenComplete(
              (v, e) ->
                  main.execute(
                      () -> {
                        if (e != null) {
                          fail(s, "prefix.remove-failed", e);
                          return;
                        }
                        core.prefixesChanged();
                        audit.record(
                            "prefix-removed", Values.of("player", s.getName(), "group", group));
                        messages.send(s, existed ? "prefix.removed" : "prefix.none");
                      }));
      return;
    }
    if (args.length != 2) {
      usage(s);
      return;
    }
    if (!allowed(s, "tabprefix.webpref")) return;
    Player p = player(s);
    if (p == null) return;
    String current = group(s, null);
    if (current == null) return;
    if (!settings.get().features.codes) {
      messages.send(s, "save-code.disabled");
      return;
    }
    boolean manage = s.hasPermission("tabprefix.prefix.manage");
    java.util.concurrent.atomic.AtomicReference<String> target =
        new java.util.concurrent.atomic.AtomicReference<>();
    messages.send(s, "prefix.applying");
    graphics
        .codeGroup(args[1])
        .thenCompose(
            group -> {
              target.set(group);
              return luck.groupExistsAsync(group)
                  .thenCompose(
                      exists -> {
                        if (!exists) {
                          CompletableFuture<Void> f = new CompletableFuture<>();
                          f.completeExceptionally(
                              new IllegalArgumentException("LuckPerms group no longer exists."));
                          return f;
                        }
                        audit.record(
                            "prefix-apply-started",
                            Values.of("player", p.getName(), "group", group));
                        return graphics.apply(args[1], p.getUniqueId(), current, manage);
                      });
            })
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      if (e != null) {
                        Throwable error = Failures.root(e);
                        if (error instanceof SaveCodeException)
                          messages.send(s, ((SaveCodeException) error).messageKey());
                        else fail(s, "prefix.failed", e);
                        return;
                      }
                      core.prefixesChanged();
                      AssetDescriptor asset = graphics.snapshot().asset(target.get());
                      audit.record(
                          "prefix-applied",
                          Values.of(
                              "player",
                              p.getName(),
                              "group",
                              target.get(),
                              "type",
                              asset == null ? "text" : asset.frames() > 1 ? "GIF" : "image"));
                      messages.send(s, "prefix.applied");
                      messages.send(s, "prefix.applied-group", Values.of("group", target.get()));
                      if (asset != null && !delivery.has(p.getUniqueId(), asset.id))
                        messages.send(s, "prefix.pack-required");
                    }));
  }

  private void info(CommandSender s, String group) {
    AssetDescriptor asset = graphics.snapshot().asset(group);
    GroupTextPrefix text = texts.find(group);
    if (asset == null && text == null) {
      messages.send(s, "prefix-info.no-prefix");
      return;
    }
    messages.send(s, "prefix-info.header");
    messages.send(s, "prefix-info.group", Values.of("group", group));
    messages.send(
        s,
        "prefix-info.type",
        Values.of(
            "type", asset == null ? "text" : asset.frames() > 1 ? "GIF + text" : "image + text"));
    messages.send(s, "prefix-info.frames", Values.of("frames", asset == null ? 0 : asset.frames()));
    messages.send(
        s, "prefix-info.glyphs", Values.of("glyphs", asset == null ? 0 : asset.glyphs().length));
    if (text != null)
      messages.send(
          s,
          "text-prefix.info",
          Values.of(
              "group",
              group,
              "format",
              text.format(),
              "updated",
              Instant.ofEpochMilli(text.updatedAt()),
              "text",
              text.text()));
  }

  private void pack(CommandSender s, String[] args) {
    if (args.length < 2) {
      usage(s);
      return;
    }
    String action = args[1].toLowerCase(Locale.ROOT);
    String perm =
        action.equals("rollback") ? "tabprefix.pack.rollback" : "tabprefix.pack." + action;
    if (!allowed(s, perm)) return;
    if (action.equals("status") && args.length == 2) {
      packStatus(s);
      return;
    }
    if (action.equals("rebuild") && args.length == 2
        || action.equals("rollback") && args.length == 3) {
      if (!settings.get().features.pack) {
        messages.send(s, "resource-pack.unavailable");
        return;
      }
      messages.send(s, "resource-pack.building");
      CompletableFuture<PackRevision> future =
          action.equals("rollback") ? packs.rollback(args[2]) : packs.rebuild();
      future.whenComplete(
          (revision, e) ->
              main.execute(
                  () -> {
                    if (e != null) {
                      fail(s, "resource-pack.build-failed", e);
                      return;
                    }
                    messages.send(
                        s,
                        action.equals("rollback")
                            ? "resource-pack.rolled-back"
                            : "resource-pack.rebuild-complete");
                    packStatus(s);
                  }));
      return;
    }
    if (action.equals("send") && args.length <= 3) {
      PackRevision pack = packs.active();
      if (pack == null || !settings.get().features.pack) {
        messages.send(s, "resource-pack.unavailable");
        return;
      }
      if (args.length == 3 && args[2].equalsIgnoreCase("all")) {
        messages.send(s, "resource-pack.sent-count", Values.of("players", delivery.sendAll(true)));
        return;
      }
      Player p = args.length == 2 ? player(s) : plugin.getServer().getPlayerExact(args[2]);
      if (p == null) {
        if (args.length == 3)
          messages.send(s, "general.player-not-found", Values.of("player", args[2]));
        return;
      }
      PackRequestState.Offer offer = delivery.send(p, pack);
      messages.send(
          s,
          offer == PackRequestState.Offer.QUEUED ? "resource-pack.pending" : "resource-pack.sent");
      audit.record("pack-sent", Values.of("players", offer == PackRequestState.Offer.SEND ? 1 : 0));
      return;
    }
    usage(s);
  }

  private void packStatus(CommandSender s) {
    PackRevision revision = packs.active();
    if (revision == null) {
      messages.send(s, "resource-pack.unavailable");
      return;
    }
    messages.send(s, "resource-pack.hash", Values.of("hash", revision.hash));
    messages.send(s, "resource-pack.glyph-count", Values.of("glyphs", revision.glyphs));
    messages.send(s, "resource-pack.atlas-count", Values.of("atlases", revision.atlases));
    messages.link(s, "resource-pack.url", Collections.emptyMap(), revision.url);
  }

  private void glyphs(CommandSender s) {
    messages.send(
        s,
        "status.glyph-range",
        Values.of(
            "start",
            String.format("U+%04X", settings.get().features.start),
            "end",
            String.format("U+%04X", settings.get().features.end),
            "used",
            graphics.snapshot().glyphs(),
            "capacity",
            settings.get().features.end - settings.get().features.start + 1));
  }

  private void admin(CommandSender s, String[] args) {
    if (args.length < 2 || args.length > 3) {
      usage(s);
      return;
    }
    if (args[1].equalsIgnoreCase("recent")) {
      if (!allowed(s, "tabprefix.adminlog")) return;
      int limit = args.length == 3 ? Integer.parseInt(args[2]) : 10;
      if (limit < 1 || limit > 50) throw new IllegalArgumentException("History limit is 1..50.");
      for (String line : audit.recent(limit))
        messages.send(s, "admin-log.history-line", Values.of("line", line));
      return;
    }
    if (args.length != 2
        || !Arrays.asList("on", "off").contains(args[1].toLowerCase(Locale.ROOT))) {
      usage(s);
      return;
    }
    if (!allowed(s, "tabprefix.adminlog.toggle")) return;
    Player p = player(s);
    if (p == null) return;
    boolean enabled = args[1].equalsIgnoreCase("on");
    audit
        .toggle(p.getUniqueId(), enabled)
        .whenComplete(
            (v, e) ->
                main.execute(
                    () -> {
                      if (e != null) fail(s, "database.save-failed", e);
                      else messages.send(s, enabled ? "admin-log.enabled" : "admin-log.disabled");
                    }));
  }

  private void fail(CommandSender s, String key, Throwable error) {
    core.error("TabPrefix operation failed.", error);
    messages.send(s, key, Values.of("reason", Failures.message(error)));
  }

  @Override
  public void status(CommandSender s) {
    messages.send(
        s, "status.web", Values.of("web", editor.running() ? "listening" : "disabled/unavailable"));
    messages.send(s, "status.web-sessions", Values.of("sessions", editor.sessions()));
    messages.send(
        s,
        "status.pack",
        Values.of("pack", packs.active() == null ? "not built" : packs.active().hash));
    messages.send(
        s, "status.graphic-prefixes", Values.of("prefixes", graphics.snapshot().prefixes.size()));
    messages.send(
        s, "status.animations", Values.of("animations", graphics.snapshot().animations()));
    messages.send(s, "status.glyphs", Values.of("glyphs", graphics.snapshot().glyphs()));
  }

  @Override
  public void help(CommandSender s) {
    if (s.hasPermission("tabprefix.webeditor")) messages.send(s, "help.webeditor");
    if (s.hasPermission("tabprefix.webpref")) messages.send(s, "help.webpref");
    if (s.hasPermission("tabprefix.webpref.remove")) messages.send(s, "help.webpref-remove");
    if (s.hasPermission("tabprefix.webpref.info")) messages.send(s, "help.webpref-info");
    if (s.hasPermission("tabprefix.pack.status")) {
      messages.send(s, "help.pack-status");
      messages.send(s, "help.glyphs");
    }
    if (s.hasPermission("tabprefix.pack.rebuild")) messages.send(s, "help.pack-rebuild");
    if (s.hasPermission("tabprefix.pack.send")) messages.send(s, "help.pack-send");
    if (s.hasPermission("tabprefix.pack.rollback")) messages.send(s, "help.pack-rollback");
    if (s.hasPermission("tabprefix.adminlog.toggle")) {
      messages.send(s, "help.adminlog-on");
      messages.send(s, "help.adminlog-off");
    }
    if (s.hasPermission("tabprefix.adminlog")) messages.send(s, "help.adminlog-recent");
  }

  @Override
  public void validateText(GroupTextPrefix prefix) {
    new me.snowsun.tabprefix.presentation.message.TextRenderer(settings.get())
        .prefixComponent(prefix.text(), prefix.format());
  }

  @Override
  public void textChanged(CommandSender s, String group, boolean removed) {
    audit.record(
        removed ? "prefix-removed" : "prefix-applied",
        Values.of("player", s.getName(), "group", group, "type", "text"));
  }

  @Override
  public void complete(CommandSender s, String[] args, List<String> out) {
    if (args.length == 1) {
      for (String action : Arrays.asList("webeditor", "webpref", "adminlog"))
        if (s.hasPermission("tabprefix." + action)) out.add(action);
      if (s.hasPermission("tabprefix.pack.status")) {
        out.add("pack");
        out.add("glyphs");
      }
    } else if (args[0].equalsIgnoreCase("webeditor")
        && args.length == 2
        && s.hasPermission("tabprefix.prefix.manage")) out.addAll(luck.groups());
    else if (args[0].equalsIgnoreCase("webpref")) {
      if (args.length == 2) {
        if (s.hasPermission("tabprefix.webpref.info")) out.add("info");
        if (s.hasPermission("tabprefix.webpref.remove")) out.add("remove");
      } else if (args.length == 3 && s.hasPermission("tabprefix.prefix.manage"))
        out.addAll(luck.groups());
    } else if (args[0].equalsIgnoreCase("pack")) {
      if (args.length == 2)
        for (String action : Arrays.asList("status", "rebuild", "send", "rollback"))
          if (s.hasPermission("tabprefix.pack." + action)) out.add(action);
      if (args.length == 3
          && args[1].equalsIgnoreCase("send")
          && s.hasPermission("tabprefix.pack.send")) {
        out.add("all");
        for (Player p : plugin.getServer().getOnlinePlayers()) out.add(p.getName());
      }
    } else if (args[0].equalsIgnoreCase("adminlog") && args.length == 2) {
      if (s.hasPermission("tabprefix.adminlog.toggle")) out.addAll(Arrays.asList("on", "off"));
      if (s.hasPermission("tabprefix.adminlog")) out.add("recent");
    }
  }
}
