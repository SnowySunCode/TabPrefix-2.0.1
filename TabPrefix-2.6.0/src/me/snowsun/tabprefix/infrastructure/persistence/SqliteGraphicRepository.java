package me.snowsun.tabprefix.infrastructure.persistence;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import me.snowsun.tabprefix.application.port.GraphicRepository;
import me.snowsun.tabprefix.domain.*;

/** Codes, glyph allocation and the text/graphic replacement commit together. */
public final class SqliteGraphicRepository implements GraphicRepository {
  private final SqliteDatabase db;

  public SqliteGraphicRepository(SqliteDatabase db) {
    this.db = db;
  }

  @Override
  public CompletableFuture<GraphicSnapshot> load() {
    return db.execute(SqliteGraphicRepository::load);
  }

  @Override
  public CompletableFuture<Void> register(AssetDescriptor a) {
    return db.execute(
        c -> {
          try (PreparedStatement s =
              c.prepareStatement("INSERT INTO assets VALUES(?,?,?,?,?,?,?,?)")) {
            s.setString(1, a.id.toString());
            s.setString(2, a.sourceFormat);
            s.setInt(3, a.width);
            s.setInt(4, a.height);
            s.setInt(5, a.renderHeight);
            s.setInt(6, a.ascent);
            s.setString(7, join(a.delays()));
            s.setLong(8, a.createdAt);
            s.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public CompletableFuture<Void> saveDraft(
      PrefixDraft d, String hash, boolean single, boolean bound) {
    return db.execute(
        c ->
            transaction(
                c,
                () -> {
                  try (PreparedStatement s =
                      c.prepareStatement(
                          "SELECT COUNT(*) FROM editor_drafts d JOIN save_codes s ON"
                              + " s.draft_id=d.draft_id WHERE owner=? AND expires_at>? AND (used_at"
                              + " IS NULL OR single_use=0)")) {
                    s.setString(1, d.owner.toString());
                    s.setLong(2, d.createdAt);
                    try (ResultSet r = s.executeQuery()) {
                      if (r.next() && r.getInt(1) >= 8)
                        throw new IllegalArgumentException(
                            "At most eight active drafts per player.");
                    }
                  }
                  try (PreparedStatement s =
                      c.prepareStatement("INSERT INTO editor_drafts VALUES(?,?,?,?,?,?,?,?)")) {
                    s.setString(1, d.id.toString());
                    s.setString(2, d.owner.toString());
                    s.setString(3, d.group);
                    s.setString(4, id(d.assetId));
                    s.setString(5, d.text);
                    s.setString(6, d.format.name());
                    s.setLong(7, d.createdAt);
                    s.setLong(8, d.expiresAt);
                    s.executeUpdate();
                  }
                  try (PreparedStatement s =
                      c.prepareStatement("INSERT INTO save_codes VALUES(?,?,?,?,NULL)")) {
                    s.setString(1, hash);
                    s.setString(2, d.id.toString());
                    s.setBoolean(3, single);
                    s.setBoolean(4, bound);
                    s.executeUpdate();
                  }
                  return null;
                }));
  }

  @Override
  public CompletableFuture<String> codeGroup(String hash) {
    return db.execute(
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT group_name FROM editor_drafts d JOIN save_codes s ON"
                      + " s.draft_id=d.draft_id WHERE code_hash=?")) {
            s.setString(1, hash);
            try (ResultSet r = s.executeQuery()) {
              if (!r.next()) throw new SaveCodeException(SaveCodeException.Reason.INVALID);
              return r.getString(1);
            }
          }
        });
  }

  @Override
  public CompletableFuture<GraphicSnapshot> apply(
      String hash, UUID actor, String group, boolean manage, int start, int end, long now) {
    return db.execute(
        c ->
            transaction(
                c,
                () -> {
                  String target, asset, text, format;
                  try (PreparedStatement s =
                      c.prepareStatement(
                          "SELECT d.*,s.single_use,s.bind_player,s.used_at FROM editor_drafts d"
                              + " JOIN save_codes s ON s.draft_id=d.draft_id WHERE code_hash=?")) {
                    s.setString(1, hash);
                    try (ResultSet r = s.executeQuery()) {
                      if (!r.next()) throw new SaveCodeException(SaveCodeException.Reason.INVALID);
                      if (r.getLong("expires_at") <= now)
                        throw new SaveCodeException(SaveCodeException.Reason.EXPIRED);
                      if (r.getBoolean("single_use") && r.getObject("used_at") != null)
                        throw new SaveCodeException(SaveCodeException.Reason.USED);
                      if (r.getBoolean("bind_player")
                          && !r.getString("owner").equals(actor.toString()))
                        throw new SaveCodeException(SaveCodeException.Reason.OWNER);
                      target = r.getString("group_name");
                      if (!manage && !target.equals(GroupTextPrefix.normalizeGroup(group)))
                        throw new SaveCodeException(SaveCodeException.Reason.GROUP);
                      asset = r.getString("asset_id");
                      text = r.getString("prefix_text");
                      format = r.getString("text_format");
                    }
                  }
                  if (asset != null) allocate(c, asset, start, end);
                  try (PreparedStatement s =
                      c.prepareStatement(
                          "INSERT INTO group_visual_prefixes VALUES(?,?,?,?) ON"
                              + " CONFLICT(group_name) DO UPDATE SET"
                              + " asset_id=excluded.asset_id,updated_by=excluded.updated_by,updated_at=excluded.updated_at")) {
                    s.setString(1, target);
                    s.setString(2, asset);
                    s.setString(3, actor.toString());
                    s.setLong(4, now);
                    s.executeUpdate();
                  }
                  if (text == null) {
                    try (PreparedStatement s =
                        c.prepareStatement("DELETE FROM group_text_prefixes WHERE group_name=?")) {
                      s.setString(1, target);
                      s.executeUpdate();
                    }
                  } else {
                    try (PreparedStatement s =
                        c.prepareStatement(
                            "INSERT INTO group_text_prefixes VALUES(?,?,?,?,?) ON"
                                + " CONFLICT(group_name) DO UPDATE SET"
                                + " prefix_text=excluded.prefix_text,text_format=excluded.text_format,updated_by=excluded.updated_by,updated_at=excluded.updated_at")) {
                      s.setString(1, target);
                      s.setString(2, text);
                      s.setString(3, format);
                      s.setString(4, actor.toString());
                      s.setLong(5, now);
                      s.executeUpdate();
                    }
                  }
                  try (PreparedStatement s =
                      c.prepareStatement("UPDATE save_codes SET used_at=? WHERE code_hash=?")) {
                    s.setLong(1, now);
                    s.setString(2, hash);
                    s.executeUpdate();
                  }
                  return load(c);
                }));
  }

  public CompletableFuture<GraphicSnapshot> assignAsset(UUID id, int start, int end) {
    return db.execute(
        c ->
            transaction(
                c,
                () -> {
                  allocate(c, id.toString(), start, end);
                  return load(c);
                }));
  }

  private static void allocate(Connection c, String asset, int start, int end) throws SQLException {
    int frames;
    try (PreparedStatement s = c.prepareStatement("SELECT delays FROM assets WHERE asset_id=?")) {
      s.setString(1, asset);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) throw new SaveCodeException(SaveCodeException.Reason.INVALID);
        frames = parse(r.getString(1)).length;
      }
    }
    int count;
    try (PreparedStatement s =
        c.prepareStatement("SELECT COUNT(*) FROM asset_glyphs WHERE asset_id=?")) {
      s.setString(1, asset);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        count = r.getInt(1);
      }
    }
    if (count == 0) {
      int next;
      try (Statement s = c.createStatement();
          ResultSet r = s.executeQuery("SELECT MAX(codepoint) FROM asset_glyphs")) {
        r.next();
        next = r.getObject(1) == null ? start : Math.max(start, r.getInt(1) + 1);
      }
      if ((long) next + frames - 1 > end)
        throw new SaveCodeException(SaveCodeException.Reason.CAPACITY);
      try (PreparedStatement s = c.prepareStatement("INSERT INTO asset_glyphs VALUES(?,?,?)")) {
        for (int i = 0; i < frames; i++) {
          s.setString(1, asset);
          s.setInt(2, i);
          s.setInt(3, next + i);
          s.addBatch();
        }
        s.executeBatch();
      }
    } else if (count != frames) throw new SQLException("Incomplete glyph allocation.");
  }

  @Override
  public CompletableFuture<GraphicSnapshot> remove(String group) {
    String target = GroupTextPrefix.normalizeGroup(group);
    return db.execute(
        c ->
            transaction(
                c,
                () -> {
                  for (String table : Arrays.asList("group_visual_prefixes", "group_text_prefixes"))
                    try (PreparedStatement s =
                        c.prepareStatement("DELETE FROM " + table + " WHERE group_name=?")) {
                      s.setString(1, target);
                      s.executeUpdate();
                    }
                  return load(c);
                }));
  }

  @Override
  public CompletableFuture<List<UUID>> cleanup(long now) {
    return db.execute(
        c ->
            transaction(
                c,
                () -> {
                  try (PreparedStatement s =
                      c.prepareStatement(
                          "UPDATE editor_drafts SET asset_id=NULL WHERE expires_at<? AND asset_id"
                              + " NOT IN (SELECT asset_id FROM asset_glyphs) AND asset_id NOT IN"
                              + " (SELECT asset_id FROM group_visual_prefixes WHERE asset_id IS NOT"
                              + " NULL)")) {
                    s.setLong(1, now);
                    s.executeUpdate();
                  }
                  try (PreparedStatement s =
                      c.prepareStatement("DELETE FROM editor_drafts WHERE expires_at<?")) {
                    s.setLong(1, now - 86400000L);
                    s.executeUpdate();
                  }
                  List<UUID> removed = new ArrayList<>();
                  try (PreparedStatement s =
                      c.prepareStatement(
                          "SELECT asset_id FROM assets WHERE created_at<? AND asset_id NOT IN"
                              + " (SELECT asset_id FROM asset_glyphs) AND asset_id NOT IN (SELECT"
                              + " asset_id FROM editor_drafts WHERE asset_id IS NOT NULL) AND"
                              + " asset_id NOT IN (SELECT asset_id FROM group_visual_prefixes WHERE"
                              + " asset_id IS NOT NULL)")) {
                    s.setLong(1, now - 3600000L);
                    try (ResultSet r = s.executeQuery()) {
                      while (r.next()) removed.add(UUID.fromString(r.getString(1)));
                    }
                  }
                  try (PreparedStatement s =
                      c.prepareStatement("DELETE FROM assets WHERE asset_id=?")) {
                    for (UUID asset : removed) {
                      s.setString(1, asset.toString());
                      s.addBatch();
                    }
                    s.executeBatch();
                  }
                  return removed;
                }));
  }

  private interface Tx<T> {
    T run() throws Exception;
  }

  private static <T> T transaction(Connection c, Tx<T> action) throws Exception {
    c.setAutoCommit(false);
    try {
      T result = action.run();
      c.commit();
      return result;
    } catch (Exception e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  private static GraphicSnapshot load(Connection c) throws SQLException {
    Map<UUID, List<Integer>> allocated = new HashMap<>();
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM asset_glyphs ORDER BY asset_id,frame_index")) {
      while (r.next()) {
        UUID id = UUID.fromString(r.getString("asset_id"));
        List<Integer> codes = allocated.computeIfAbsent(id, k -> new ArrayList<>());
        if (r.getInt("frame_index") != codes.size())
          throw new SQLException("Non-contiguous frame allocation.");
        codes.add(r.getInt("codepoint"));
      }
    }
    Map<UUID, AssetDescriptor> assets = new HashMap<>();
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM assets")) {
      while (r.next()) {
        UUID id = UUID.fromString(r.getString("asset_id"));
        List<Integer> codes = allocated.getOrDefault(id, Collections.emptyList());
        int[] glyphs = new int[codes.size()];
        for (int i = 0; i < codes.size(); i++) glyphs[i] = codes.get(i);
        assets.put(
            id,
            new AssetDescriptor(
                id,
                r.getString("source_format"),
                r.getInt("width"),
                r.getInt("height"),
                r.getInt("render_height"),
                r.getInt("ascent"),
                parse(r.getString("delays")),
                glyphs,
                r.getLong("created_at")));
      }
    }
    Map<String, GraphicPrefix> prefixes = new HashMap<>();
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM group_visual_prefixes")) {
      while (r.next()) {
        String group = r.getString("group_name");
        prefixes.put(
            group,
            new GraphicPrefix(
                group,
                uuid(r.getString("asset_id")),
                uuid(r.getString("updated_by")),
                r.getLong("updated_at")));
      }
    }
    Map<String, GroupTextPrefix> texts = new HashMap<>();
    try (Statement s = c.createStatement();
        ResultSet r = s.executeQuery("SELECT * FROM group_text_prefixes")) {
      while (r.next()) {
        String group = r.getString("group_name");
        texts.put(
            group,
            new GroupTextPrefix(
                group,
                r.getString("prefix_text"),
                TextFormat.valueOf(r.getString("text_format")),
                uuid(r.getString("updated_by")),
                r.getLong("updated_at")));
      }
    }
    return new GraphicSnapshot(assets, prefixes, texts);
  }

  private static String id(UUID id) {
    return id == null ? null : id.toString();
  }

  private static UUID uuid(String id) {
    return id == null ? null : UUID.fromString(id);
  }

  private static String join(int[] values) {
    StringJoiner join = new StringJoiner(",");
    for (int value : values) join.add(String.valueOf(value));
    return join.toString();
  }

  private static int[] parse(String value) {
    String[] parts = value.split(",");
    int[] result = new int[parts.length];
    for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]);
    return result;
  }
}
