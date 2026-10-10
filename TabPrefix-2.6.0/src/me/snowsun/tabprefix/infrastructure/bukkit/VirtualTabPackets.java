package me.snowsun.tabprefix.infrastructure.bukkit;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Fixed 80-slot layout; real profiles, signed-chat sessions and game modes remain intact. */
public final class VirtualTabPackets {
  public static final class Cell {
    public final String text;
    public final Player player;

    public Cell(String text, Player player) {
      this.text = text;
      this.player = player;
    }
  }

  private static final class State {
    final String[] text = new String[80];
    final UUID[] binding = new UUID[80];
    final int[] latency = new int[80];

    State() {
      Arrays.fill(latency, -1);
    }
  }

  private final Logger logger;
  private final NativeTabAccess nativeTab = new NativeTabAccess();
  private final Map<UUID, State> viewers = new HashMap<>();
  private final UUID[] ids = new UUID[80];
  private boolean failed;

  public VirtualTabPackets(JavaPlugin plugin) {
    this(plugin.getLogger());
  }

  public VirtualTabPackets(Logger logger) {
    this.logger = logger;
    for (int i = 0; i < 80; i++)
      ids[i] =
          UUID.nameUUIDFromBytes(("TabPrefix/display/slot/" + i).getBytes(StandardCharsets.UTF_8));
  }

  /** Invisible/vanished targets must be excluded by the caller. */
  public boolean render(Player viewer, List<Cell> cells, Collection<Player> visible, long tick) {
    if (failed) {
      restore(viewer, visible);
      return false;
    }
    if (cells.size() != 80) throw new IllegalArgumentException("Expected 80 TAB cells");
    try {
      nativeTab.initialize(viewer);
      State before = viewers.computeIfAbsent(viewer.getUniqueId(), key -> new State()),
          next = new State();
      Object add = nativeTab.packet("ADD_PLAYER", Collections.emptyList()),
          update = nativeTab.packet("UPDATE_DISPLAY_NAME", Collections.emptyList()),
          latency = nativeTab.packet("UPDATE_LATENCY", Collections.emptyList());
      List<Object> additions = new ArrayList<>(),
          names = new ArrayList<>(),
          pings = new ArrayList<>();
      for (int i = 0; i < 80; i++) {
        Cell cell = cells.get(i);
        UUID binding = cell.player == null ? null : cell.player.getUniqueId();
        int nextPing = cell.player == null ? 0 : ping(cell.player);
        if (before.text[i] == null || !Objects.equals(before.binding[i], binding))
          additions.add(nativeTab.entry(add, ids[i], i, cell));
        else {
          if (!before.text[i].equals(cell.text))
            names.add(nativeTab.entry(update, ids[i], i, cell));
          if (before.latency[i] != nextPing) pings.add(nativeTab.entry(latency, ids[i], i, cell));
        }
        next.text[i] = cell.text;
        next.binding[i] = binding;
        next.latency[i] = nextPing;
      }
      send(viewer, add, additions);
      send(viewer, update, names);
      send(viewer, latency, pings);
      viewers.put(viewer.getUniqueId(), next);
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      if (!failed)
        logger.log(
            java.util.logging.Level.WARNING,
            "Fixed TAB layout unavailable; returning to the normal player list.",
            e);
      failed = true;
      restore(viewer, visible);
      return false;
    }
  }

  private void send(Player viewer, Object packet, List<Object> entries)
      throws ReflectiveOperationException {
    if (entries.isEmpty()) return;
    nativeTab.entries(packet, entries);
    nativeTab.send(viewer, packet);
  }

  public void restore(Player viewer, Collection<Player> visible) {
    if (viewers.remove(viewer.getUniqueId()) == null) return;
    try {
      nativeTab.remove(viewer, ids);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      logger.log(
          java.util.logging.Level.WARNING, "Could not restore TAB layout for a recipient.", e);
    }
  }

  public void forget(UUID viewer) {
    viewers.remove(viewer);
  }

  public int ping(Player player) {
    return nativeTab.ping(player);
  }

  public boolean available() {
    return !failed;
  }

  public String diagnostic() {
    return "tabLayout=" + (failed ? "fallback" : nativeTab.family());
  }
}
