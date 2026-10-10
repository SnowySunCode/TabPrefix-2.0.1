package me.snowsun.tabprefix.infrastructure.bukkit;

import java.util.*;
import java.util.logging.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** One display-name packet per viewer, with immutable modern entries copied intact. */
public final class ViewerTabPackets {
  private final Logger logger;
  private final NativeTabAccess nativeTab = new NativeTabAccess();
  private boolean available = true;

  public ViewerTabPackets(JavaPlugin plugin) {
    this(plugin.getLogger());
  }

  public ViewerTabPackets(Logger logger) {
    this.logger = logger;
  }

  public boolean send(Player viewer, Map<Player, String> names) {
    if (!available || names.isEmpty()) return available;
    try {
      nativeTab.initialize(viewer);
      Object packet = nativeTab.packet("UPDATE_DISPLAY_NAME", names.keySet());
      nativeTab.rename(packet, names);
      nativeTab.send(viewer, packet);
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
      available = false;
      logger.log(
          Level.WARNING,
          "Per-viewer TAB formatting unavailable; text fallback remains active.",
          error);
      return false;
    }
  }

  public String diagnostic() {
    return "tabNames=" + (available ? nativeTab.family() : "fallback");
  }
}
