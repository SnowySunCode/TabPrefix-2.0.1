package me.snowsun.tabprefix.infrastructure.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.snowsun.tabprefix.application.port.GroupPrefixRepository;
import me.snowsun.tabprefix.domain.GroupTextPrefix;
import me.snowsun.tabprefix.domain.TextFormat;

public final class SqliteGroupPrefixRepository implements GroupPrefixRepository {
  private final SqliteDatabase database;

  public SqliteGroupPrefixRepository(SqliteDatabase database) {
    this.database = database;
  }

  @Override
  public CompletableFuture<List<GroupTextPrefix>> findAll() {
    return database.execute(
        connection -> {
          List<GroupTextPrefix> result = new ArrayList<>();
          try (PreparedStatement statement =
                  connection.prepareStatement(
                      "SELECT * FROM group_text_prefixes ORDER BY group_name");
              ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
              String actor = rows.getString("updated_by");
              result.add(
                  new GroupTextPrefix(
                      rows.getString("group_name"),
                      rows.getString("prefix_text"),
                      TextFormat.valueOf(rows.getString("text_format")),
                      actor == null ? null : UUID.fromString(actor),
                      rows.getLong("updated_at")));
            }
          }
          return result;
        });
  }

  @Override
  public CompletableFuture<Void> save(GroupTextPrefix prefix) {
    return database.execute(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO"
                      + " group_text_prefixes(group_name,prefix_text,text_format,updated_by,updated_at)"
                      + " VALUES(?,?,?,?,?) ON CONFLICT(group_name) DO UPDATE SET"
                      + " prefix_text=excluded.prefix_text, "
                      + "text_format=excluded.text_format,updated_by=excluded.updated_by,updated_at=excluded.updated_at")) {
            statement.setString(1, prefix.group());
            statement.setString(2, prefix.text());
            statement.setString(3, prefix.format().name());
            statement.setString(
                4, prefix.updatedBy() == null ? null : prefix.updatedBy().toString());
            statement.setLong(5, prefix.updatedAt());
            statement.executeUpdate();
          }
          return null;
        });
  }

  @Override
  public CompletableFuture<Boolean> delete(String group) {
    String key = GroupTextPrefix.normalizeGroup(group);
    return database.execute(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM group_text_prefixes WHERE group_name=?")) {
            statement.setString(1, key);
            return statement.executeUpdate() != 0;
          }
        });
  }
}
