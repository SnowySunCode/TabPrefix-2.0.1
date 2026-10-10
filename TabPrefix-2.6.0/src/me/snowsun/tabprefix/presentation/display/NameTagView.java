package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

/** Only owns its own teams, and never removes entries from a foreign team. */
public final class NameTagView implements AutoCloseable {
  private final JavaPlugin plugin;
  private final int textLimit;
  private final Map<UUID, Scoreboard> previous = new HashMap<>(), owned = new HashMap<>();
  private final Map<Scoreboard, Map<UUID, Team>> teams = new IdentityHashMap<>();

  public NameTagView(JavaPlugin plugin) {
    this(
        plugin,
        plugin == null
            ? MinecraftVersion.parse("1.16.5")
            : MinecraftVersion.parse(plugin.getServer().getBukkitVersion()));
  }

  public NameTagView(JavaPlugin plugin, MinecraftVersion version) {
    this.plugin = plugin;
    textLimit = version.teamTextLimit();
  }

  public Scoreboard board(Player viewer, boolean own) {
    if (!own) {
      restore(viewer);
      return viewer.getScoreboard();
    }
    Scoreboard board = owned.get(viewer.getUniqueId());
    if (board == null || viewer.getScoreboard() != board) {
      previous.put(viewer.getUniqueId(), viewer.getScoreboard());
      board = plugin.getServer().getScoreboardManager().getNewScoreboard();
      owned.put(viewer.getUniqueId(), board);
      viewer.setScoreboard(board);
    }
    return board;
  }

  public void apply(Scoreboard board, Player target, String prefix) {
    apply(board, target, prefix, "", "ALWAYS", "ALWAYS");
  }

  public void apply(
      Scoreboard board,
      Player target,
      String prefix,
      String suffix,
      String visibility,
      String collision) {
    apply(board, target, prefix, suffix, visibility, collision, -1);
  }

  public boolean apply(
      Scoreboard board,
      Player target,
      String prefix,
      String suffix,
      String visibility,
      String collision,
      int position) {
    String uuid = target.getUniqueId().toString().replace("-", "");
    String name =
        position < 0
            ? "tp" + uuid.substring(0, 14)
            : "tp"
                + String.format(java.util.Locale.ROOT, "%05d", Math.min(99999, position))
                + uuid.substring(0, 9);
    Map<UUID, Team> map = teams.computeIfAbsent(board, k -> new HashMap<>());
    Team team = map.get(target.getUniqueId()), existing = board.getEntryTeam(target.getName());
    if (existing != null && existing != team) return false;
    if (team != null && !team.getName().equals(name)) {
      unregister(team);
      map.remove(target.getUniqueId());
      team = null;
    }
    if (team != null && board.getTeam(name) != team) {
      map.remove(target.getUniqueId());
      team = null;
    }
    if (team == null) {
      if (board.getTeam(name) != null) return false;
      team = board.registerNewTeam(name);
      map.put(target.getUniqueId(), team);
    }
    String clipped = truncate(prefix, textLimit);
    if (!team.getPrefix().equals(clipped)) team.setPrefix(clipped);
    String tail = truncate(suffix, textLimit);
    if (!team.getSuffix().equals(tail)) team.setSuffix(tail);
    Team.OptionStatus visible = Team.OptionStatus.valueOf(visibility);
    Team.OptionStatus collide = Team.OptionStatus.valueOf(collision);
    if (team.getOption(Team.Option.NAME_TAG_VISIBILITY) != visible)
      team.setOption(Team.Option.NAME_TAG_VISIBILITY, visible);
    if (team.getOption(Team.Option.COLLISION_RULE) != collide)
      team.setOption(Team.Option.COLLISION_RULE, collide);
    if (!team.hasEntry(target.getName())) team.addEntry(target.getName());
    return true;
  }

  public void remove(Scoreboard board, UUID id) {
    Map<UUID, Team> map = teams.get(board);
    Team team = map == null ? null : map.remove(id);
    if (team != null) unregister(team);
  }

  public void retain(Set<Scoreboard> active) {
    Iterator<Map.Entry<Scoreboard, Map<UUID, Team>>> it = teams.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Scoreboard, Map<UUID, Team>> e = it.next();
      if (!active.contains(e.getKey())) {
        for (Team team : e.getValue().values()) unregister(team);
        it.remove();
      }
    }
  }

  public void forget(UUID id) {
    for (Map<UUID, Team> map : teams.values()) {
      Team team = map.remove(id);
      if (team != null) unregister(team);
    }
    owned.remove(id);
    previous.remove(id);
  }

  private void restore(Player viewer) {
    Scoreboard board = owned.remove(viewer.getUniqueId()),
        before = previous.remove(viewer.getUniqueId());
    if (board != null && before != null && viewer.getScoreboard() == board)
      viewer.setScoreboard(before);
  }

  private static void unregister(Team team) {
    try {
      team.unregister();
    } catch (IllegalStateException ignored) {
    }
  }

  public static String truncate(String text, int maximum) {
    int n = 0;
    while (n < text.length()) {
      int length = 1;
      if (text.charAt(n) == '§') {
        if (n + 1 >= text.length()) break;
        length = (text.charAt(n + 1) == 'x' || text.charAt(n + 1) == 'X') ? 14 : 2;
        if (n + length > text.length()) break;
      } else if (Character.isHighSurrogate(text.charAt(n))) length = 2;
      if (n + length > maximum) break;
      n += length;
    }
    return text.substring(0, n);
  }

  @Override
  public void close() {
    if (plugin != null) for (Player player : plugin.getServer().getOnlinePlayers()) restore(player);
    for (Map<UUID, Team> map : teams.values()) for (Team team : map.values()) unregister(team);
    teams.clear();
    previous.clear();
    owned.clear();
  }
}
