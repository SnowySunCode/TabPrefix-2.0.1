package me.snowsun.tabprefix.infrastructure.audit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Logger;
import me.snowsun.tabprefix.application.port.*;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.infrastructure.persistence.SqliteDatabase;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.util.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Bounded file history, rotated persistent logs and per-administrator chat preferences. */
public final class AuditService implements AuditSink, AutoCloseable {
  @FunctionalInterface
  public interface ChatNotifier {
    void send(String event, Map<String, String> values, Predicate<UUID> enabled, String permission);
  }

  private final Path root;
  private final Logger logger;
  private final BiFunction<String, Map<String, String>, String> formatter;
  private final ChatNotifier chat;
  private final SqliteDatabase db;
  private final AsyncWorker worker = new AsyncWorker("Audit", 1, 256);
  private final Map<UUID, Boolean> preferences = new ConcurrentHashMap<>();
  private final Deque<String> history = new ArrayDeque<>();
  private volatile FeatureSettings settings;

  public AuditService(
      JavaPlugin plugin,
      MessageService messages,
      MainThreadExecutor main,
      SqliteDatabase db,
      PluginSettings settings) {
    this(
        plugin.getDataFolder().toPath(),
        plugin.getLogger(),
        db,
        settings.features,
        (event, values) -> messages.plain("admin-log." + event, values),
        (event, values, enabled, permission) ->
            main.execute(
                () -> {
                  for (Player player : plugin.getServer().getOnlinePlayers())
                    if (player.hasPermission(permission) && enabled.test(player.getUniqueId()))
                      messages.send(player, "admin-log." + event, values);
                }));
  }

  public AuditService(
      Path root,
      Logger logger,
      SqliteDatabase db,
      FeatureSettings settings,
      BiFunction<String, Map<String, String>, String> formatter,
      ChatNotifier chat) {
    this.root = root;
    this.logger = logger;
    this.db = db;
    this.settings = settings;
    this.formatter = formatter;
    this.chat = chat;
  }

  public CompletableFuture<Void> initialize() {
    return db.execute(
            c -> {
              try (Statement s = c.createStatement();
                  ResultSet rows = s.executeQuery("SELECT * FROM admin_preferences")) {
                while (rows.next())
                  preferences.put(UUID.fromString(rows.getString(1)), rows.getBoolean(2));
              }
              return null;
            })
        .thenCompose(
            v ->
                worker.submit(
                    () -> {
                      Path file = file(settings);
                      if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        try (BufferedReader reader =
                            Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                          String line;
                          while ((line = reader.readLine()) != null) remember(line);
                        }
                      return null;
                    }));
  }

  public void reload(PluginSettings settings) {
    this.settings = settings.features;
  }

  public boolean enabled(UUID id) {
    return preferences.getOrDefault(id, true);
  }

  public CompletableFuture<Void> toggle(UUID id, boolean enabled) {
    return db.execute(
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "INSERT INTO admin_preferences VALUES(?,?) ON CONFLICT(player_uuid) DO UPDATE SET"
                      + " enabled=excluded.enabled")) {
            s.setString(1, id.toString());
            s.setBoolean(2, enabled);
            s.executeUpdate();
          }
          preferences.put(id, enabled);
          return null;
        });
  }

  @Override
  public void record(String event, Map<String, String> values) {
    FeatureSettings f = settings;
    if (!f.audit || !f.auditEvents.contains(event)) return;
    Map<String, String> copy = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    String plain = formatter.apply(event, copy);
    String line = Instant.now() + " " + event + " " + plain.replace('\n', ' ').replace('\r', ' ');
    remember(line);
    if (f.auditChat) chat.send(event, copy, this::enabled, f.auditPermission);
    if (f.auditFile)
      worker
          .submit(
              () -> {
                Path file = file(f);
                Files.createDirectories(file.getParent());
                if (Files.isSymbolicLink(file))
                  throw new IOException("Audit file cannot be a symbolic link.");
                if (Files.exists(file) && Files.size(file) >= f.rotateBytes) {
                  Files.deleteIfExists(file.resolveSibling(f.auditName + "." + f.keepLogs));
                  for (int i = f.keepLogs - 1; i >= 1; i--) {
                    Path from = file.resolveSibling(f.auditName + "." + i);
                    if (Files.exists(from))
                      Files.move(
                          from,
                          file.resolveSibling(f.auditName + "." + (i + 1)),
                          StandardCopyOption.REPLACE_EXISTING);
                  }
                  Files.move(
                      file,
                      file.resolveSibling(f.auditName + ".1"),
                      StandardCopyOption.REPLACE_EXISTING);
                }
                Files.write(
                    file,
                    (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
                return null;
              })
          .whenComplete(
              (v, e) -> {
                if (e != null) logger.warning("Cannot write audit record: " + Failures.message(e));
              });
  }

  public CompletableFuture<Void> flush() {
    return worker.submit(() -> null);
  }

  public synchronized List<String> recent(int limit) {
    List<String> lines = new ArrayList<>(history);
    return new ArrayList<>(
        lines.subList(Math.max(0, lines.size() - Math.max(1, Math.min(50, limit))), lines.size()));
  }

  private synchronized void remember(String line) {
    history.addLast(line);
    while (history.size() > 200) history.removeFirst();
  }

  private Path file(FeatureSettings f) {
    return root.resolve(f.auditName);
  }

  @Override
  public void close() {
    worker.closeGracefully(2000);
  }
}
