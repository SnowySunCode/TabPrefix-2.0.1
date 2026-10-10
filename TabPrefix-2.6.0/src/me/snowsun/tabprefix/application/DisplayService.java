package me.snowsun.tabprefix.application;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.persistence.SqliteDatabase;
import me.snowsun.tabprefix.infrastructure.web.HttpProblem;

/** Serial SQLite commits with optimistic version guards and immutable runtime snapshots. */
public final class DisplayService {
  public static final class Snapshot {
    public final DisplayDesign design;
    public final int revision;

    public Snapshot(DisplayDesign design, int revision) {
      this.design = design;
      this.revision = revision;
    }

    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("revision", revision);
      j.add("design", design.json());
      return j;
    }
  }

  private final SqliteDatabase db;
  private final Map<UUID, PlayerDisplaySettings> preferences = new ConcurrentHashMap<>();
  private final PlayerDisplaySettings defaults = new PlayerDisplaySettings(new JsonObject());
  private volatile Snapshot snapshot;
  private volatile Runnable changed = () -> {};

  public DisplayService(SqliteDatabase db, boolean tags) {
    this.db = db;
    snapshot = new Snapshot(DisplayDesign.defaults(tags), 1);
  }

  public Snapshot snapshot() {
    return snapshot;
  }

  public void onChanged(Runnable changed) {
    this.changed = changed;
  }

  public PlayerDisplaySettings preferences(UUID owner) {
    return preferences.getOrDefault(owner, defaults);
  }

  public CompletableFuture<Void> initialize() {
    return db.execute(
        c -> {
          try (PreparedStatement p =
              c.prepareStatement(
                  "INSERT OR IGNORE INTO display_design(id,revision,document) VALUES(1,1,?)")) {
            p.setString(1, snapshot.design.json().toString());
            p.executeUpdate();
          }
          try (Statement s = c.createStatement();
              ResultSet r =
                  s.executeQuery("SELECT revision,document FROM display_design WHERE id=1")) {
            if (!r.next()) throw new SQLException("Missing display design");
            snapshot =
                new Snapshot(
                    new DisplayDesign(new JsonParser().parse(r.getString(2)).getAsJsonObject()),
                    r.getInt(1));
          }
          try (Statement s = c.createStatement();
              ResultSet r =
                  s.executeQuery("SELECT player_uuid,document FROM player_display_preferences")) {
            while (r.next())
              preferences.put(
                  UUID.fromString(r.getString(1)),
                  new PlayerDisplaySettings(
                      new JsonParser().parse(r.getString(2)).getAsJsonObject()));
          }
          return null;
        });
  }

  public CompletableFuture<Snapshot> save(DisplayDesign design, int expected, UUID author) {
    return db.execute(
        c -> {
          try (PreparedStatement p =
              c.prepareStatement(
                  "UPDATE display_design SET"
                      + " revision=revision+1,document=?,updated_by=?,updated_at=? WHERE id=1 AND"
                      + " revision=?")) {
            p.setString(1, design.json().toString());
            p.setString(2, author.toString());
            p.setLong(3, System.currentTimeMillis());
            p.setInt(4, expected);
            if (p.executeUpdate() != 1)
              throw new HttpProblem(
                  409,
                  "Another administrator saved this design. Reload before applying your changes.");
          }
          Snapshot next = new Snapshot(design, expected + 1);
          snapshot = next;
          changed.run();
          return next;
        });
  }

  public CompletableFuture<PlayerDisplaySettings> savePreferences(
      UUID owner, PlayerDisplaySettings settings) {
    return db.execute(
        c -> {
          try (PreparedStatement p =
              c.prepareStatement(
                  "INSERT INTO player_display_preferences(player_uuid,document) VALUES(?,?) ON"
                      + " CONFLICT(player_uuid) DO UPDATE SET document=excluded.document")) {
            p.setString(1, owner.toString());
            p.setString(2, settings.json().toString());
            p.executeUpdate();
          }
          preferences.put(owner, settings);
          changed.run();
          return settings;
        });
  }
}
