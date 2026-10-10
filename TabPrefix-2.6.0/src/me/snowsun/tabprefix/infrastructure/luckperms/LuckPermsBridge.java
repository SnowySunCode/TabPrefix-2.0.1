package me.snowsun.tabprefix.infrastructure.luckperms;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import me.snowsun.tabprefix.application.port.GroupDirectory;
import me.snowsun.tabprefix.domain.PlayerIdentity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** The standalone branch never resolves optional LuckPerms API classes. */
public final class LuckPermsBridge implements GroupDirectory, AutoCloseable {
  interface Adapter extends GroupDirectory, AutoCloseable {
    PlayerIdentity identity(Player player, boolean primary);

    JsonArray groupMetadata();

    boolean groupExists(String group);

    List<String> groups();

    void listen(boolean updates, Consumer<UUID> refresh, Runnable all);

    void close();
  }

  private final Adapter adapter;
  private Supplier<List<String>> local = () -> Collections.singletonList("default");

  public LuckPermsBridge(JavaPlugin plugin, Object provider) {
    adapter = provider == null ? null : new LuckPermsApiAdapter(plugin, provider);
  }

  public void localGroups(Supplier<List<String>> groups) {
    local = Objects.requireNonNull(groups);
  }

  public boolean integrated() {
    return adapter != null;
  }

  public PlayerIdentity identity(Player player, boolean primary) {
    if (adapter != null) return adapter.identity(player, primary);
    List<String> names = groups();
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      if (!name.equals("default") && player.hasPermission("tabprefix.group." + name))
        return new PlayerIdentity(player.getUniqueId(), name, "", "", names.size() - i);
    }
    return new PlayerIdentity(player.getUniqueId(), "default", "", "", 0);
  }

  public List<String> groups() {
    if (adapter != null) return adapter.groups();
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (String s : local.get()) if (valid(s)) names.add(s.toLowerCase(Locale.ROOT));
    names.add("default");
    return new ArrayList<>(names);
  }

  public JsonArray groupMetadata() {
    if (adapter != null) return adapter.groupMetadata();
    JsonArray a = new JsonArray();
    List<String> names = groups();
    for (int i = 0; i < names.size(); i++) {
      JsonObject row = new JsonObject();
      row.addProperty("group", names.get(i));
      row.addProperty("weight", names.get(i).equals("default") ? 0 : names.size() - i);
      a.add(row);
    }
    return a;
  }

  private static boolean valid(String name) {
    return name != null && name.matches("[a-zA-Z0-9_.-]{1,128}");
  }

  public boolean groupExists(String name) {
    return adapter != null ? adapter.groupExists(name) : valid(name);
  }

  public CompletableFuture<Boolean> groupExistsAsync(String name) {
    return adapter != null
        ? adapter.groupExistsAsync(name)
        : CompletableFuture.completedFuture(valid(name));
  }

  public void listen(boolean updates, Consumer<UUID> refresh, Runnable all) {
    if (adapter != null) adapter.listen(updates, refresh, all);
  }

  public void close() {
    if (adapter != null) adapter.close();
  }
}
