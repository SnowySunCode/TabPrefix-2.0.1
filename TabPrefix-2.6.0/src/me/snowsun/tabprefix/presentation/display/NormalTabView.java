package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import java.util.function.Function;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import me.snowsun.tabprefix.infrastructure.bukkit.ViewerTabPackets;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Per-recipient names with differential updates and an owned Bukkit text fallback. */
public final class NormalTabView implements AutoCloseable {
  private final JavaPlugin plugin;
  private final ViewerTabPackets packets;
  private final TabListView fallback;
  private final Map<UUID, Map<UUID, String>> applied = new HashMap<>();

  public NormalTabView(JavaPlugin plugin) {
    this(plugin, MinecraftVersion.parse("1.16.5"));
  }

  public NormalTabView(JavaPlugin plugin, MinecraftVersion version) {
    this.plugin = plugin;
    fallback = new TabListView(version);
    packets = new ViewerTabPackets(plugin);
  }

  public String original(Player target) {
    String name = fallback.original(target);
    return name == null ? target.getName() : name;
  }

  public void render(Player viewer, Map<Player, String> names, Function<Player, String> plain) {
    Map<UUID, String> next = new LinkedHashMap<>();
    names.forEach((player, text) -> next.put(player.getUniqueId(), text));
    if (next.equals(applied.get(viewer.getUniqueId()))) return;
    if (!packets.send(viewer, names))
      for (Player target : names.keySet()) fallback.applyName(target, plain.apply(target));
    applied.put(viewer.getUniqueId(), next);
  }

  public void forget(UUID viewer) {
    applied.remove(viewer);
    fallback.forget(viewer);
  }

  public String diagnostic() {
    return packets.diagnostic();
  }

  public void close() {
    for (Player target : plugin.getServer().getOnlinePlayers()) fallback.restore(target);
    for (Player viewer : plugin.getServer().getOnlinePlayers()) {
      Map<Player, String> names = new LinkedHashMap<>();
      for (Player target : plugin.getServer().getOnlinePlayers())
        if (viewer.canSee(target)) names.put(target, original(target));
      packets.send(viewer, names);
    }
    applied.clear();
  }
}
