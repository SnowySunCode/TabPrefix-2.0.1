package me.snowsun.tabprefix.infrastructure.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Owns a bounded serial worker. Every operation owns and closes its connection. */
public final class SqliteDatabase implements AutoCloseable {
  @FunctionalInterface
  public interface Operation<T> {
    T run(Connection connection) throws Exception;
  }

  private final Path file;
  private final Logger logger;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(256),
          action -> {
            Thread thread = new Thread(action, "TabPrefix-SQLite");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  public SqliteDatabase(Path file, Logger logger) {
    this.file = file;
    this.logger = logger;
  }

  public Path file() {
    return file;
  }

  public CompletableFuture<Void> initialize() {
    return execute(
        connection -> {
          try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
              if (!result.next())
                throw new IllegalStateException("Cannot read SQLite schema version.");
              version = result.getInt(1);
            }
            if (version > 2)
              throw new IllegalStateException(
                  "Database schema is newer than this plugin: " + version);
            connection.setAutoCommit(false);
            try {
              statement.execute(
                  "CREATE TABLE IF NOT EXISTS group_text_prefixes ("
                      + "group_name TEXT PRIMARY KEY, prefix_text TEXT NOT NULL, "
                      + "text_format TEXT NOT NULL CHECK(text_format IN ('LEGACY','MINIMESSAGE')), "
                      + "updated_by TEXT, updated_at INTEGER NOT NULL)");
              statement.execute(
                  "CREATE TABLE IF NOT EXISTS assets (asset_id TEXT PRIMARY KEY, source_format TEXT"
                      + " NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL, render_height"
                      + " INTEGER NOT NULL, ascent INTEGER NOT NULL, delays TEXT NOT NULL,"
                      + " created_at INTEGER NOT NULL)");
              statement.execute(
                  "CREATE TABLE IF NOT EXISTS asset_glyphs (asset_id TEXT NOT NULL REFERENCES"
                      + " assets(asset_id), frame_index INTEGER NOT NULL, codepoint INTEGER NOT"
                      + " NULL UNIQUE, PRIMARY KEY(asset_id,frame_index))");
              statement.execute(
                  "CREATE TABLE IF NOT EXISTS group_visual_prefixes (group_name TEXT PRIMARY KEY,"
                      + " asset_id TEXT REFERENCES assets(asset_id), updated_by TEXT, updated_at"
                      + " INTEGER NOT NULL)");
              statement.execute(
                  "CREATE TABLE IF NOT EXISTS editor_drafts (draft_id TEXT PRIMARY KEY, owner TEXT"
                      + " NOT NULL, group_name TEXT NOT NULL, asset_id TEXT REFERENCES"
                      + " assets(asset_id), prefix_text TEXT, text_format TEXT NOT NULL, created_at"
                      + " INTEGER NOT NULL, expires_at INTEGER NOT NULL)");
              statement.execute(
                  "CREATE TABLE IF NOT EXISTS save_codes (code_hash TEXT PRIMARY KEY, draft_id TEXT"
                      + " NOT NULL REFERENCES editor_drafts(draft_id) ON DELETE CASCADE, single_use"
                      + " INTEGER NOT NULL, bind_player INTEGER NOT NULL, used_at INTEGER)");
              statement.execute(
                  "CREATE INDEX IF NOT EXISTS drafts_owner_expiry ON"
                      + " editor_drafts(owner,expires_at)");
              statement.execute(
                  "CREATE TABLE IF NOT EXISTS admin_preferences (player_uuid TEXT PRIMARY KEY,"
                      + " enabled INTEGER NOT NULL)");
              statement.execute("PRAGMA user_version=2");
              connection.commit();
            } catch (Exception error) {
              connection.rollback();
              throw error;
            } finally {
              connection.setAutoCommit(true);
            }
          }
          return null;
        });
  }

  public <T> CompletableFuture<T> execute(Operation<T> operation) {
    CompletableFuture<T> result = new CompletableFuture<>();
    if (closed.get()) {
      result.completeExceptionally(new IllegalStateException("Database is closed."));
      return result;
    }
    try {
      worker.execute(
          () -> {
            try {
              Files.createDirectories(file.getParent());
              T value;
              try (Connection connection =
                  new org.sqlite.JDBC()
                      .connect("jdbc:sqlite:" + file, new java.util.Properties())) {
                try (Statement statement = connection.createStatement()) {
                  statement.execute("PRAGMA busy_timeout=2000");
                  statement.execute("PRAGMA foreign_keys=ON");
                  statement.execute("PRAGMA synchronous=NORMAL");
                }
                value = operation.run(connection);
              }
              result.complete(value);
            } catch (Exception | LinkageError error) {
              result.completeExceptionally(error);
            } finally {
              if (closed.get()) deregisterOwnedDriver();
            }
          });
    } catch (RejectedExecutionException error) {
      result.completeExceptionally(error);
    }
    return result;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    worker.shutdown();
    try {
      if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
        logger.warning("SQLite worker is still finishing queued operations.");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } finally {
      deregisterOwnedDriver();
    }
  }

  private void deregisterOwnedDriver() {
    java.util.Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
    while (drivers.hasMoreElements()) {
      java.sql.Driver driver = drivers.nextElement();
      if (driver.getClass().getClassLoader() == SqliteDatabase.class.getClassLoader()
          && driver.getClass().getName().equals("org.sqlite.JDBC")) {
        try {
          DriverManager.deregisterDriver(driver);
        } catch (java.sql.SQLException error) {
          logger.warning("Cannot deregister SQLite driver: " + error.getMessage());
        }
      }
    }
  }
}
