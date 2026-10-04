package me.snowsun.tabprefix.presentation.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.snowsun.tabprefix.application.PrefixService;
import me.snowsun.tabprefix.application.port.CoreControl;
import me.snowsun.tabprefix.application.port.GroupDirectory;
import me.snowsun.tabprefix.application.port.MainThreadExecutor;
import me.snowsun.tabprefix.domain.GroupTextPrefix;
import me.snowsun.tabprefix.domain.RuntimeStatus;
import me.snowsun.tabprefix.domain.TextFormat;
import me.snowsun.tabprefix.presentation.message.MessageSink;
import me.snowsun.tabprefix.util.Failures;
import me.snowsun.tabprefix.util.Values;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Only implemented commands are advertised or suggested. */
public final class TabPrefixCommand implements CommandExecutor, TabCompleter {
  private final ExtraCommands extras;
  private final CoreControl core;
  private final PrefixService prefixes;
  private final GroupDirectory groups;
  private final MessageSink messages;
  private final MainThreadExecutor executor;

  public TabPrefixCommand(
      CoreControl core,
      PrefixService prefixes,
      GroupDirectory groups,
      MessageSink messages,
      MainThreadExecutor executor) {
    this(core, prefixes, groups, messages, executor, null);
  }

  public TabPrefixCommand(
      CoreControl core,
      PrefixService prefixes,
      GroupDirectory groups,
      MessageSink messages,
      MainThreadExecutor executor,
      ExtraCommands extras) {
    this.extras = extras;
    this.core = core;
    this.prefixes = prefixes;
    this.groups = groups;
    this.messages = messages;
    this.executor = executor;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      if (!allowed(sender, "tabprefix.use")) return true;
      String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
      if ("help".equals(action)) {
        help(sender);
        return true;
      }
      if ("doctor".equals(action)) {
        if (args.length != 1) {
          usage(sender);
          return true;
        }
        if (allowed(sender, "tabprefix.status")) doctor(sender);
        return true;
      }
      if ("status".equals(action)) {
        if (args.length != 1) {
          usage(sender);
          return true;
        }
        if (allowed(sender, "tabprefix.status")) status(sender);
        return true;
      }
      if (extras != null && extras.handle(sender, action, args)) return true;
      String permission =
          "reload".equals(action)
              ? "tabprefix.reload"
              : "prefix".equals(action) ? "tabprefix.prefix.manage" : null;
      if (permission == null) {
        messages.send(sender, "general.unknown-command");
        return true;
      }
      if (!allowed(sender, permission)) return true;
      if (!core.ready()) {
        messages.send(sender, "general.not-ready");
        return true;
      }
      if ("reload".equals(action)) {
        if (args.length != 1) {
          usage(sender);
          return true;
        }
        try {
          core.reload();
          messages.send(sender, "general.reloaded");
        } catch (Exception error) {
          core.error("Configuration reload refused.", error);
          messages.send(
              sender, "general.reload-failed", Values.of("reason", Failures.message(error)));
        }
      } else prefix(sender, args);
    } catch (IllegalArgumentException error) {
      messages.send(sender, "text-prefix.invalid", Values.of("reason", error.getMessage()));
    } catch (RuntimeException error) {
      core.error("Command failed.", error);
      messages.send(sender, "general.internal-error");
    }
    return true;
  }

  private boolean allowed(CommandSender sender, String permission) {
    if (sender.hasPermission(permission)) return true;
    messages.send(sender, "general.no-permission");
    return false;
  }

  private void usage(CommandSender sender) {
    messages.send(sender, "general.invalid-arguments");
    help(sender);
  }

  private void help(CommandSender sender) {
    messages.send(sender, "help.header");
    messages.send(sender, "help.core-help");
    if (sender.hasPermission("tabprefix.status")) {
      messages.send(sender, "help.status");
      messages.send(sender, "help.doctor");
    }
    if (sender.hasPermission("tabprefix.reload")) messages.send(sender, "help.reload");
    if (sender.hasPermission("tabprefix.prefix.manage")) {
      messages.send(sender, "help.prefix-set");
      messages.send(sender, "help.prefix-remove");
      messages.send(sender, "help.prefix-info");
    }
    if (extras != null) extras.help(sender);
  }

  private void doctor(CommandSender sender) {
    RuntimeStatus status = core.status();
    String report =
        "[TabPrefix] version="
            + status.version
            + ", server="
            + status.platform
            + ", minecraft="
            + status.minecraft
            + ", ready="
            + core.ready()
            + ", text-prefixes="
            + prefixes.count()
            + ", java="
            + System.getProperty("java.version")
            + ", command=TabPrefix, replies=Bukkit/Spigot";
    // Remains useful even if a customised message template is broken.
    sender.sendMessage(report);
    if (!(sender instanceof ConsoleCommandSender)) core.diagnostic(report);
  }

  private void status(CommandSender sender) {
    RuntimeStatus status = core.status();
    messages.send(sender, "status.header");

    send(sender, "status.version", "version", status.version);
    send(sender, "status.minecraft", "minecraft", status.minecraft);
    send(sender, "status.platform", "platform", status.platform);
    send(sender, "status.luckperms", "luckperms", "connected");
    send(sender, "status.database", "database", core.ready() ? "ready" : "starting");
    send(sender, "status.prefixes", "prefixes", prefixes.count());
    if (extras != null) extras.status(sender);
  }

  private void send(CommandSender sender, String key, String name, Object value) {
    messages.send(sender, key, Values.of(name, value));
  }

  private void prefix(CommandSender sender, String[] args) {
    if (args.length < 3) {
      usage(sender);
      return;
    }
    String action = args[1].toLowerCase(Locale.ROOT);
    String group = GroupTextPrefix.normalizeGroup(args[2]);
    if ("info".equals(action) && args.length == 3) {
      GroupTextPrefix found = prefixes.find(group);
      if (found == null) {
        messages.send(sender, "text-prefix.none", Values.of("group", group));
        return;
      }
      messages.send(
          sender,
          "text-prefix.info",
          Values.of(
              "group",
              group,
              "text",
              found.text(),
              "format",
              found.format(),
              "updated",
              java.time.Instant.ofEpochMilli(found.updatedAt())));
      return;
    }
    if ("remove".equals(action) && args.length == 3) {
      complete(sender, prefixes.remove(group), group, false);
      return;
    }
    if (!"set".equals(action) || args.length < 4) {
      usage(sender);
      return;
    }
    int start = 3;
    TextFormat explicit = null;
    if ("--legacy".equalsIgnoreCase(args[start])) {
      explicit = TextFormat.LEGACY;
      start++;
    } else if ("--mini".equalsIgnoreCase(args[start])) {
      explicit = TextFormat.MINIMESSAGE;
      start++;
    }
    if (start >= args.length) {
      usage(sender);
      return;
    }
    String text = String.join(" ", Arrays.copyOfRange(args, start, args.length));
    UUID actor = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
    GroupTextPrefix value =
        new GroupTextPrefix(
            group,
            text,
            explicit == null ? TextFormat.detect(text) : explicit,
            actor,
            System.currentTimeMillis());
    if (extras != null) extras.validateText(value);
    messages.send(sender, "prefix.applying");
    CompletableFuture<Boolean> operation =
        groups
            .groupExistsAsync(group)
            .thenCompose(
                exists -> {
                  if (!exists) return CompletableFuture.completedFuture(false);
                  return prefixes.save(value).thenApply(ignored -> true);
                });
    complete(sender, operation, group, true);
  }

  private void complete(
      CommandSender sender, CompletableFuture<Boolean> operation, String group, boolean saving) {
    operation.whenComplete(
        (changed, error) ->
            executor.execute(
                () -> {
                  if (error != null) {
                    core.error("Cannot persist prefix for " + group, error);
                    messages.send(sender, "database.save-failed");
                    return;
                  }
                  if (!changed) {
                    messages.send(
                        sender,
                        saving ? "text-prefix.group-not-found" : "text-prefix.none",
                        Values.of("group", group));
                    return;
                  }
                  core.prefixesChanged();
                  if (extras != null) extras.textChanged(sender, group, !saving);
                  messages.send(
                      sender,
                      saving ? "text-prefix.saved" : "text-prefix.removed",
                      Values.of("group", group));
                }));
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    List<String> options = new ArrayList<>();
    if (!sender.hasPermission("tabprefix.use")) return options;
    if (args.length == 1) {
      options.add("help");
      if (sender.hasPermission("tabprefix.status")) {
        options.add("status");
        options.add("doctor");
      }
      if (sender.hasPermission("tabprefix.reload")) options.add("reload");
      if (sender.hasPermission("tabprefix.prefix.manage")) options.add("prefix");
    } else if (sender.hasPermission("tabprefix.prefix.manage")
        && "prefix".equalsIgnoreCase(args[0])) {
      if (args.length == 2) options.addAll(Arrays.asList("set", "remove", "info"));
      else if (args.length == 3) options.addAll(groups.groups());
      else if (args.length == 4 && "set".equalsIgnoreCase(args[1]))
        options.addAll(Arrays.asList("--legacy", "--mini"));
    }
    if (extras != null) extras.complete(sender, args, options);
    String input = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    options.removeIf(value -> !value.toLowerCase(Locale.ROOT).startsWith(input));
    return options;
  }
}
