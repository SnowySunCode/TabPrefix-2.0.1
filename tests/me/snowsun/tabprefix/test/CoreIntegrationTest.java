package me.snowsun.tabprefix.test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import me.snowsun.tabprefix.application.PrefixService;
import me.snowsun.tabprefix.application.port.CoreControl;
import me.snowsun.tabprefix.application.port.GroupDirectory;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.GroupTextPrefix;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import me.snowsun.tabprefix.domain.RuntimeStatus;
import me.snowsun.tabprefix.domain.TextFormat;
import me.snowsun.tabprefix.infrastructure.persistence.SqliteDatabase;
import me.snowsun.tabprefix.infrastructure.persistence.SqliteGroupPrefixRepository;
import me.snowsun.tabprefix.presentation.command.TabPrefixCommand;
import me.snowsun.tabprefix.presentation.display.ChatFormat;
import me.snowsun.tabprefix.presentation.message.MessageSink;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import me.snowsun.tabprefix.util.SafePaths;
import me.snowsun.tabprefix.util.Values;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;

/** Runs against the final relocated JAR; uses a real native SQLite database. */
public final class CoreIntegrationTest {
  private static final Logger LOG = Logger.getLogger("TabPrefix-CoreTest");
  private static int checks;

  private static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    checks++;
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(8, TimeUnit.SECONDS);
  }

  private static YamlConfiguration yaml(Path source) throws Exception {
    YamlConfiguration config = new YamlConfiguration();
    try (java.io.Reader input =
        Files.newBufferedReader(source, java.nio.charset.StandardCharsets.UTF_8)) {
      config.load(input);
    }
    return config;
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]);
    Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "core-");
    try {
      resources(project, root);
      formatting(project, root);
      storage(root);
      commands(root);
      bounds(root);
      System.out.println(
          "PASS: "
              + checks
              + " checks. Real SQLite, persistence/recovery, serialized writes, "
              + "command permissions, formatting, paths and final relocated JAR.");
    } finally {
      try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
        for (Path path :
            (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
          Files.deleteIfExists(path);
      }
    }
  }

  private static void resources(Path project, Path root) throws Exception {
    YamlConfiguration config = yaml(project.resolve("resources/config.yml"));
    new PluginSettings(config, root);
    YamlConfiguration catalog = yaml(project.resolve("resources/messages.yml"));
    String[] keys = {
      "general.reload-failed",
      "help.prefix-set",
      "help.prefix-info",
      "text-prefix.saved",
      "text-prefix.info",
      "status.source-package",
      "general.no-permission"
    };
    for (String key : keys) check(catalog.isString(key), "Required message " + key);
    PluginDescriptionFile description;
    try (java.io.InputStream input =
        Files.newInputStream(project.resolve("resources/plugin.yml"))) {
      description = new PluginDescriptionFile(input);
    }
    check("me.snowsun.tabprefix.TabPrefix".equals(description.getMain()), "Manifest main class");
    check(description.getDepend().contains("LuckPerms"), "Hard LuckPerms dependency");
    check(
        yaml(project.resolve("resources/plugin.yml"))
            .isConfigurationSection("permissions.tabprefix.prefix.manage"),
        "Management permission");
    YamlConfiguration partial = new YamlConfiguration();
    partial.loadFromString("general:\n  debug: true\n");
    partial.setDefaults(config);
    partial.options().copyDefaults(true);
    check(new PluginSettings(partial, root).debug, "Defaults merged with existing config");
    config.set("display.chat.enabled", "not-a-boolean");
    try {
      new PluginSettings(config, root);
      throw new AssertionError("Invalid boolean accepted");
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  private static void formatting(Path project, Path root) throws Exception {
    YamlConfiguration config = yaml(project.resolve("resources/config.yml"));
    TextRenderer renderer = new TextRenderer(new PluginSettings(config, root));
    String attack = "<click:run_command:'/op Someone'><red>payload</red></click>";
    Component safe = renderer.template("<green><text></green>", Values.of("text", attack));
    check(attack.equals(renderer.plain(safe)), "Unparsed dynamic values");
    check(
        !GsonComponentSerializer.gson().serialize(safe).contains("\"clickEvent\""),
        "No injected click event");
    check(renderer.prefix("&a[Admin] ", TextFormat.LEGACY).contains("§a"), "Legacy prefix colors");
    check(
        renderer.prefix("<gold>[VIP]</gold>", TextFormat.MINIMESSAGE).contains("§6"),
        "MiniMessage prefix colors");
    Component interactive =
        renderer.template(
            "<click:open_url:'https://example.org'><hover:show_text:'Info'>Link</hover></click>",
            Collections.emptyMap());
    String json = GsonComponentSerializer.gson().serialize(interactive);
    check(json.contains("open_url") && json.contains("show_text"), "Clickable and hover messages");
    config.set("minimessage.allow-click-events", false);
    config.set("minimessage.allow-hover-events", false);
    config.set("minimessage.allow-colors", false);
    config.set("minimessage.allow-decorations", false);
    TextRenderer restricted = new TextRenderer(new PluginSettings(config, root));
    Component clean =
        restricted.template(
            "<red><bold><click:run_command:'/op X'>Text</click></bold></red>",
            Collections.emptyMap());
    json = GsonComponentSerializer.gson().serialize(clean);
    check(
        !json.contains("\"color\"")
            && !json.contains("\"bold\":true")
            && !json.contains("clickEvent"),
        "Formatting policy");
    config.set("minimessage.enabled", false);
    TextRenderer plain = new TextRenderer(new PluginSettings(config, root));
    check(
        "Hello User"
            .equals(
                plain.plain(
                    plain.template("<yellow>Hello <name></yellow>", Values.of("name", "User")))),
        "MiniMessage disabled with placeholders retained");
    String format = ChatFormat.prepend("[100%] ", "<%1$s> %2$s");
    check(
        "[100%] §r<Alex> message".equals(String.format(format, "Alex", "message")),
        "Percent-safe chat formatting");
    String help = yaml(project.resolve("resources/messages.yml")).getString("help.prefix-set");
    check(
        renderer.plain(renderer.template(help, Collections.emptyMap())).contains("<group>"),
        "Literal help placeholders");
  }

  private static void storage(Path root) throws Exception {
    Path file = root.resolve("prefixes.db");
    SqliteDatabase database = new SqliteDatabase(file, LOG);
    PrefixService service = new PrefixService(new SqliteGroupPrefixRepository(database));
    UUID actor = UUID.randomUUID();
    try {
      await(database.initialize());
      await(service.initialize());
      List<CompletableFuture<Void>> writes = new ArrayList<>();
      for (int i = 0; i < 20; i++)
        writes.add(
            service.save(
                new GroupTextPrefix(
                    "Admin",
                    "<gold>Привет 🦊 " + i + "</gold>",
                    TextFormat.MINIMESSAGE,
                    actor,
                    i)));
      for (CompletableFuture<Void> write : writes) await(write);
      check(
          service.count() == 1 && service.find("ADMIN").text().contains("19"),
          "Mutation order and normalized cache");
      check(
          await(new SqliteGroupPrefixRepository(database).findAll()).get(0).text().contains("19"),
          "Committed database matches cache");
      String original = service.find("admin").text();
      try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement statement = lock.createStatement()) {
        lock.setAutoCommit(false);
        statement.executeUpdate("UPDATE group_text_prefixes SET updated_at=updated_at+1");
        try {
          await(
              service.save(
                  new GroupTextPrefix("admin", "must not publish", TextFormat.LEGACY, actor, 100)));
          throw new AssertionError("Locked database unexpectedly accepted write");
        } catch (java.util.concurrent.ExecutionException expected) {
          checks++;
        }
        check(
            service.find("admin").text().equals(original),
            "Failed write leaves visible cache unchanged");
        lock.rollback();
      }
      await(service.save(new GroupTextPrefix("admin", "recovered", TextFormat.LEGACY, actor, 200)));
      check(
          "recovered".equals(service.find("admin").text()),
          "Failed operation does not poison mutation queue");
      GroupTextPrefix literal =
          new GroupTextPrefix("quote'; select 1;--", "§aЮникод ", TextFormat.LEGACY, actor, 300);
      await(service.save(literal));
      check(service.count() == 2, "Parameterized group values cannot execute SQL");
    } finally {
      service.close();
      database.close();
    }

    database = new SqliteDatabase(file, LOG);
    service = new PrefixService(new SqliteGroupPrefixRepository(database));
    try {
      await(database.initialize());
      await(service.initialize());
      check("recovered".equals(service.find("admin").text()), "Persistence after restart");
      check(actor.equals(service.find("admin").updatedBy()), "Persisted actor UUID");
      check(service.find("quote'; select 1;--").text().contains(""), "Persistent Unicode glyph");
      check(
          await(service.remove("admin")) && service.find("admin") == null,
          "Committed removal updates cache");
      check(!await(service.remove("admin")), "Repeated removal reports no change");
    } finally {
      service.close();
      database.close();
    }
    SqliteDatabase newer = new SqliteDatabase(root.resolve("newer.db"), LOG);
    try {
      await(
          newer.execute(
              connection -> {
                try (Statement s = connection.createStatement()) {
                  s.execute("PRAGMA user_version=99");
                }
                return null;
              }));
      try {
        await(newer.initialize());
        throw new AssertionError("Newer schema accepted");
      } catch (java.util.concurrent.ExecutionException expected) {
        checks++;
      }
      check(
          await(
                  newer.execute(
                      connection -> {
                        try (Statement s = connection.createStatement();
                            ResultSet rows = s.executeQuery("PRAGMA user_version")) {
                          rows.next();
                          return rows.getInt(1);
                        }
                      }))
              == 99,
          "Newer schema remains intact");
    } finally {
      newer.close();
    }
  }

  private static void commands(Path root) throws Exception {
    SqliteDatabase database = new SqliteDatabase(root.resolve("commands.db"), LOG);
    PrefixService prefixes = new PrefixService(new SqliteGroupPrefixRepository(database));
    Recorder output = new Recorder();
    FakeCore core = new FakeCore();
    GroupDirectory groups =
        new GroupDirectory() {
          public CompletableFuture<Boolean> groupExistsAsync(String name) {
            return CompletableFuture.completedFuture(name.equals("admin"));
          }

          public List<String> groups() {
            return Arrays.asList("admin", "default");
          }
        };
    try {
      await(database.initialize());
      await(prefixes.initialize());
      TabPrefixCommand commands =
          new TabPrefixCommand(core, prefixes, groups, output, Runnable::run);
      CommandSender user = sender("tabprefix.use");
      commands.onCommand(user, null, "lptab", new String[] {"prefix", "set", "admin", "forbidden"});
      check(
          output.keys.contains("general.no-permission") && prefixes.count() == 0,
          "Unauthorized command cannot persist data");
      check(
          !commands.onTabComplete(user, null, "lptab", new String[] {""}).contains("prefix"),
          "Permission-aware completion");
      CommandSender admin =
          sender(
              "tabprefix.use", "tabprefix.prefix.manage", "tabprefix.status", "tabprefix.reload");
      commands.onCommand(
          admin, null, "lptab", new String[] {"prefix", "set", "missing", "unknown"});
      check(
          output.keys.contains("text-prefix.group-not-found") && prefixes.count() == 0,
          "Unknown group rejected");
      commands.onCommand(
          admin,
          null,
          "lptab",
          new String[] {"prefix", "set", "admin", "--mini", "<gold>VIP</gold>"});
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!output.keys.contains("text-prefix.saved") && System.nanoTime() < deadline)
        Thread.yield();
      check(
          output.keys.contains("text-prefix.saved")
              && prefixes.find("admin").format() == TextFormat.MINIMESSAGE,
          "Command persists formatted prefix");
      commands.onCommand(admin, null, "lptab", new String[] {"prefix", "info", "admin"});
      check(output.keys.contains("text-prefix.info"), "Prefix inspection");
      core.reloadFails = true;
      commands.onCommand(admin, null, "lptab", new String[] {"reload"});
      check(output.keys.contains("general.reload-failed"), "Reload failure reported");
      core.ready = false;
      commands.onCommand(admin, null, "lptab", new String[] {"prefix", "remove", "admin"});
      check(
          output.keys.contains("general.not-ready") && prefixes.find("admin") != null,
          "Readiness guard");
    } finally {
      prefixes.close();
      database.close();
    }
  }

  private static CommandSender sender(String... permissions) {
    Set<String> allowed = new HashSet<>(Arrays.asList(permissions));
    return (CommandSender)
        Proxy.newProxyInstance(
            CoreIntegrationTest.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> {
              if (method.getName().equals("hasPermission"))
                return allowed.contains(String.valueOf(args[0]));
              if (method.getName().equals("getName")) return "Console";
              if (method.getName().equals("toString")) return "TestSender";
              if (method.getReturnType() == boolean.class) return false;
              return null;
            });
  }

  private static final class Recorder implements MessageSink {
    final List<String> keys = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void send(CommandSender sender, String key) {
      keys.add(key);
    }

    public void send(CommandSender sender, String key, Map<String, String> variables) {
      keys.add(key);
    }
  }

  private static final class FakeCore implements CoreControl {
    boolean ready = true, reloadFails;

    public boolean ready() {
      return ready;
    }

    public RuntimeStatus status() {
      return new RuntimeStatus("2.0.0", "1.16.5", "Test", ready, 0);
    }

    public void reload() throws Exception {
      if (reloadFails) throw new Exception("invalid candidate");
    }

    public void prefixesChanged() {}

    public void error(String action, Throwable error) {}
  }

  private static void bounds(Path root) throws Exception {
    check(
        MinecraftVersion.parse("1.16").supported()
            && MinecraftVersion.parse("1.16.5-R0.1-SNAPSHOT").supported(),
        "Supported version endpoints");
    check(
        !MinecraftVersion.parse("1.15.2").supported()
            && !MinecraftVersion.parse("1.16.6").supported()
            && !MinecraftVersion.parse("1.17").supported(),
        "Unsupported versions excluded");
    try {
      SafePaths.inside(root, "../outside.db");
      throw new AssertionError("Traversal accepted");
    } catch (java.io.IOException expected) {
      checks++;
    }
    try {
      SafePaths.inside(root, root.resolve("absolute.db").toString());
      throw new AssertionError("Absolute path accepted");
    } catch (java.io.IOException expected) {
      checks++;
    }
  }
}
