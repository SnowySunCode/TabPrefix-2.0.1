package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

/** Only owns its own teams, and never removes entries from a foreign team. */
public final class NameTagView implements AutoCloseable {
  private final JavaPlugin plugin;
  private final Map<UUID, Scoreboard> previous = new HashMap<>(), owned = new HashMap<>();
  private final Map<Scoreboard, Map<UUID, Team>> teams = new IdentityHashMap<>();

  public NameTagView(JavaPlugin plugin) {
    this.plugin = plugin;
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
    String name = "tp" + target.getUniqueId().toString().replace("-", "").substring(0, 14);
    Map<UUID, Team> map = teams.computeIfAbsent(board, k -> new HashMap<>());
    Team team = map.get(target.getUniqueId()), existing = board.getEntryTeam(target.getName());
    if (existing != null && existing != team) return;
    if (team != null && board.getTeam(name) != team) {
      map.remove(target.getUniqueId());
      team = null;
    }
    if (team == null) {
      if (board.getTeam(name) != null) return;
      team = board.registerNewTeam(name);
      map.put(target.getUniqueId(), team);
    }
    String clipped = truncate(prefix, 64);
    if (!team.getPrefix().equals(clipped)) team.setPrefix(clipped);
    if (!team.hasEntry(target.getName())) team.addEntry(target.getName());
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
    for (Player player : plugin.getServer().getOnlinePlayers()) restore(player);
    for (Map<UUID, Team> map : teams.values()) for (Team team : map.values()) unregister(team);
    teams.clear();
    previous.clear();
    owned.clear();
  }
}
