package me.snowsun.tabprefix.test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.*;
import me.snowsun.tabprefix.application.PrefixService;
import me.snowsun.tabprefix.application.port.*;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.persistence.*;
import me.snowsun.tabprefix.presentation.command.*;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.util.Values;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Uses the production MessageService and final JAR, recording native Bukkit/Spigot calls. */
public final class MessageDeliveryTest {
  private static int checks;

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]);
    Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "delivery-");
    Logger logger = Logger.getLogger("TabPrefix-DeliveryTest-" + UUID.randomUUID());
    logger.setUseParentHandlers(false);
    List<String> warnings = new ArrayList<>();
    logger.addHandler(
        new Handler() {
          public void publish(LogRecord record) {
            warnings.add(record.getMessage());
          }

          public void flush() {}

          public void close() {}
        });
    try {
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.load(project.resolve("resources/config.yml").toFile());
      YamlConfiguration messages = new YamlConfiguration();
      messages.load(project.resolve("resources/messages.yml").toFile());
      Map<String, String> catalog = new HashMap<>();
      messages
          .getValues(true)
          .forEach(
              (key, value) -> {
                if (!(value instanceof ConfigurationSection)) catalog.put(key, (String) value);
              });
      ConfigurationSnapshot config =
          new ConfigurationSnapshot(new PluginSettings(yaml, root), catalog);
      try (MessageService service = new MessageService(logger, config)) {
        delivery(service, config, warnings, yaml, root);
        commands(root, logger, config, service);
      }
      System.out.println(
          "PASS: "
              + checks
              + " message delivery checks. Production MessageService, native Bukkit/Spigot calls,"
              + " system links, fallback and command diagnostics.");
    } finally {
      try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
        for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
          Files.deleteIfExists(p);
      }
    }
  }

  private static void delivery(
      MessageService messages,
      ConfigurationSnapshot config,
      List<String> warnings,
      YamlConfiguration yaml,
      Path root)
      throws Exception {
    Sender player = new Sender(Player.class);
    messages.send(player.value, "help.header");
    check(
        player.text.size() == 1,
        "Player reply invokes Bukkit sendMessage directly, without viewer registration");
    check(
        player.text.get(0).contains("TabPrefix") && player.text.get(0).contains("Commands:"),
        "Player receives help content");
    check(player.text.get(0).contains("§"), "Legacy colours preserved in native reply");
    check(player.rich.isEmpty(), "Ordinary help does not depend on a rich-message bridge");

    Sender console = new Sender(ConsoleCommandSender.class);
    messages.send(console.value, "general.not-ready");
    check(
        console.text.size() == 1 && console.text.get(0).contains("not ready"),
        "Console receives native reply");
    Sender block = new Sender(BlockCommandSender.class);
    messages.send(block.value, "general.players-only");
    check(
        block.text.size() == 1 && block.text.get(0).contains("player"),
        "Command block receives reply rather than an empty audience");
    Sender custom = new Sender(CommandSender.class);
    messages.send(custom.value, "general.unknown-command");
    check(custom.text.size() == 1, "Other Bukkit command senders receive replies");

    String token = "PRIVATE_SESSION_TEST_TOKEN";
    String url = "http://localhost:8765/editor/#" + token;
    messages.link(player.value, "webeditor.link", Values.of("player", "Alex", "group", "vip"), url);
    check(player.rich.size() == 1, "Editor link uses native Spigot component delivery");
    check(player.types.get(0) == ChatMessageType.SYSTEM, "Editor link is a system message");
    check(url.equals(click(player.rich.get(0))), "Editor link preserves typed URL and fragment");
    check(
        BaseComponent.toPlainText(player.rich.get(0)).contains(url), "Editor URL remains readable");
    check(player.text.size() == 1, "Successful rich delivery is not duplicated");

    Map<String, String> customized = new HashMap<>(config.messages);
    customized.put(
        "test.interactive",
        "<click:run_command:'/lptab help'><hover:show_text:'Help'><gold>Open"
            + " help</gold></hover></click>");
    messages.reload(new ConfigurationSnapshot(config.settings, customized));
    messages.send(player.value, "test.interactive");
    check(
        "/lptab help".equals(click(player.rich.get(1))),
        "Configured click event survives conversion");
    check(hasHover(player.rich.get(1)), "Configured hover event survives conversion");

    Sender fallback = new Sender(Player.class);
    fallback.failRich = true;
    messages.link(fallback.value, "webeditor.link", Collections.emptyMap(), url);
    check(
        fallback.text.size() == 1 && fallback.text.get(0).contains(url),
        "Unsupported Spigot bridge falls back to readable Bukkit URL");
    check(warnings.size() == 1, "Failed rich delivery emits a diagnostic");
    messages.link(fallback.value, "webeditor.link", Collections.emptyMap(), url);
    check(
        warnings.size() == 1 && fallback.text.size() == 2,
        "Fallback remains usable without repeated log spam");
    check(
        warnings.stream().noneMatch(text -> text.contains(token) || text.contains(url)),
        "Delivery warnings never disclose session tokens or URLs");

    Sender offline = new Sender(Player.class);
    offline.online = false;
    messages.send(offline.value, "help.header");
    messages.link(offline.value, "webeditor.link", Collections.emptyMap(), url);
    check(
        offline.text.isEmpty() && offline.rich.isEmpty(),
        "Disconnected players do not receive late callbacks");

    Map<String, String> literal = new HashMap<>(customized);
    literal.put("test.literal", "<gray><reason></gray>");
    messages.reload(new ConfigurationSnapshot(config.settings, literal));
    messages.send(custom.value, "test.literal", Values.of("reason", "<red>literal</red>"));
    check(
        custom.text.get(1).contains("<red>literal</red>"),
        "Dynamic text remains literal in native delivery");
    messages.send(custom.value, "test.missing");
    check(
        custom.text.get(2).contains("Missing message: test.missing"),
        "Missing message is visible to sender");

    YamlConfiguration disabled = new YamlConfiguration();
    disabled.loadFromString(yaml.saveToString());
    disabled.set("minimessage.allow-click-events", false);
    disabled.set("minimessage.allow-hover-events", false);
    messages.reload(new ConfigurationSnapshot(new PluginSettings(disabled, root), customized));
    Sender noEvents = new Sender(Player.class);
    messages.send(noEvents.value, "test.interactive");
    messages.link(noEvents.value, "webeditor.link", Collections.emptyMap(), url);
    check(
        noEvents.rich.isEmpty() && noEvents.text.size() == 2,
        "Disabled interactive policies use native text delivery");
    messages.reload(config);

    MessageService closed = new MessageService(Logger.getAnonymousLogger(), config);
    closed.close();
    closed.send(custom.value, "help.header");
    check(custom.text.size() == 3, "Closed message service stops delivery");
  }

  private static void commands(
      Path root, Logger logger, ConfigurationSnapshot config, MessageService messages)
      throws Exception {
    SqliteDatabase database = new SqliteDatabase(root.resolve("commands.db"), logger);
    PrefixService prefixes = new PrefixService(new SqliteGroupPrefixRepository(database));
    database.initialize().get();
    prefixes.initialize().get();
    Core core = new Core();
    GroupDirectory groups =
        new GroupDirectory() {
          public CompletableFuture<Boolean> groupExistsAsync(String group) {
            return CompletableFuture.completedFuture(group.equals("default"));
          }

          public List<String> groups() {
            return Collections.singletonList("default");
          }
        };
    try {
      TabPrefixCommand command =
          new TabPrefixCommand(core, prefixes, groups, messages, Runnable::run);
      Sender player = new Sender(Player.class);
      for (String[] arguments :
          new String[][] {{}, {"help"}, {"status"}, {"prefix"}, {"does-not-exist"}}) {
        player.text.clear();
        command.onCommand(player.value, null, "lptab", arguments);
        check(
            !player.text.isEmpty(),
            "Actual command produces native reply: " + Arrays.toString(arguments));
      }
      player.allowed = false;
      player.text.clear();
      command.onCommand(player.value, null, "lptab", new String[] {"help"});
      check(
          player.text.size() == 1 && player.text.get(0).contains("permission"),
          "Permission denial reaches native player channel");
      player.allowed = true;
      core.ready = false;
      FeatureCommands features =
          new FeatureCommands(
              null,
              core,
              null,
              prefixes,
              null,
              null,
              null,
              null,
              null,
              messages,
              Runnable::run,
              () -> config.settings);
      TabPrefixCommand guarded =
          new TabPrefixCommand(core, prefixes, groups, messages, Runnable::run, features);
      for (String action : Arrays.asList("webeditor", "webpref", "pack", "adminlog", "glyphs")) {
        player.text.clear();
        guarded.onCommand(player.value, null, "lptab", new String[] {action});
        check(
            player.text.size() == 1 && player.text.get(0).contains("not ready"),
            "Feature readiness reply is delivered: " + action);
      }
      player.text.clear();
      command.onCommand(player.value, null, "lptab", new String[] {"doctor"});
      check(
          player.text.size() == 1 && player.text.get(0).contains("ready=false"),
          "Doctor works while startup is incomplete");
      check(
          core.reports.size() == 1 && core.reports.get(0).contains("version=2.0.1"),
          "Player doctor also reports to server log boundary");
      Sender console = new Sender(ConsoleCommandSender.class);
      command.onCommand(console.value, null, "lptab", new String[] {"doctor"});
      check(
          console.text.size() == 1 && core.reports.size() == 1,
          "Console doctor sends one native report without duplicated logger output");
      check(
          command
              .onTabComplete(player.value, null, "lptab", new String[] {"doc"})
              .contains("doctor"),
          "Doctor appears in permission-aware completion");
      check(
          player.text.get(0).contains("replies=Bukkit/Spigot"),
          "Doctor identifies active reply transport");
    } finally {
      prefixes.close();
      database.close();
    }
  }

  private static String click(BaseComponent[] components) {
    for (BaseComponent component : components) {
      if (component.getClickEvent() != null) return component.getClickEvent().getValue();
      if (component.getExtra() != null) {
        String nested = click(component.getExtra().toArray(new BaseComponent[0]));
        if (nested != null) return nested;
      }
    }
    return null;
  }

  private static boolean hasHover(BaseComponent[] components) {
    for (BaseComponent component : components) {
      if (component.getHoverEvent() != null) return true;
      if (component.getExtra() != null
          && hasHover(component.getExtra().toArray(new BaseComponent[0]))) return true;
    }
    return false;
  }

  private static final class Sender {
    final List<String> text = new ArrayList<>();
    final List<BaseComponent[]> rich = new ArrayList<>();
    final List<ChatMessageType> types = new ArrayList<>();
    final CommandSender value;
    boolean online = true, allowed = true, failRich;

    Sender(Class<?> type) {
      Player.Spigot spigot =
          new Player.Spigot() {
            @Override
            public void sendMessage(ChatMessageType messageType, BaseComponent... components) {
              if (failRich) throw new UnsupportedOperationException("No component bridge");
              rich.add(components);
              types.add(messageType);
            }
          };
      value =
          (CommandSender)
              Proxy.newProxyInstance(
                  MessageDeliveryTest.class.getClassLoader(),
                  new Class<?>[] {type},
                  (proxy, method, args) -> {
                    switch (method.getName()) {
                      case "sendMessage":
                        Object body = args[args.length - 1];
                        if (body instanceof String) text.add((String) body);
                        else if (body instanceof String[])
                          text.addAll(Arrays.asList((String[]) body));
                        return null;
                      case "spigot":
                        return spigot;
                      case "isOnline":
                        return online;
                      case "hasPermission":
                      case "isOp":
                        return allowed;
                      case "getName":
                        return "DeliveryTest";
                      case "getUniqueId":
                        return UUID.fromString("00000000-0000-0000-0000-000000000001");
                      case "hashCode":
                        return System.identityHashCode(proxy);
                      case "equals":
                        return proxy == args[0];
                      case "toString":
                        return "DeliveryTestSender";
                      default:
                        return method.getReturnType() == boolean.class ? false : null;
                    }
                  });
    }
  }

  private static final class Core implements CoreControl {
    boolean ready = true;
    final List<String> reports = new ArrayList<>();

    public boolean ready() {
      return ready;
    }

    public RuntimeStatus status() {
      return new RuntimeStatus("2.0.1", "1.16.5", "API-test", ready, 0);
    }

    public void reload() {}

    public void prefixesChanged() {}

    public void error(String action, Throwable error) {
      throw new AssertionError(action, error);
    }

    public void diagnostic(String report) {
      reports.add(report);
    }
  }
}
