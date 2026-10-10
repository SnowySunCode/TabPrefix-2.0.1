package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/** Creates recipient boards only from a pristine vanilla board; respects foreign ownership. */
public final class DisplayBoards {
  private final Server server;
  private final Map<UUID, Scoreboard> owned = new HashMap<>(), previous = new HashMap<>();

  public DisplayBoards(Server server) {
    this.server = server;
  }

  public Scoreboard board(Player player, boolean need) {
    if (!need) {
      restore(player);
      return player.getScoreboard();
    }
    Scoreboard current = player.getScoreboard();
    Scoreboard ours = owned.get(player.getUniqueId());
    if (ours != null && current != ours) {
      owned.remove(player.getUniqueId());
      previous.remove(player.getUniqueId());
    }
    Scoreboard main = server.getScoreboardManager().getMainScoreboard();
    if (current == main && current.getObjectives().isEmpty() && current.getTeams().isEmpty()) {
      Scoreboard board = server.getScoreboardManager().getNewScoreboard();
      previous.put(player.getUniqueId(), current);
      owned.put(player.getUniqueId(), board);
      player.setScoreboard(board);
      return board;
    }
    return current;
  }

  public boolean personal(Scoreboard board) {
    if (board == server.getScoreboardManager().getMainScoreboard()) return false;
    int count = 0;
    for (Player p : server.getOnlinePlayers())
      if (p.getScoreboard() == board && ++count > 1) return false;
    return true;
  }

  public void restore(Player player) {
    Scoreboard ours = owned.remove(player.getUniqueId()),
        before = previous.remove(player.getUniqueId());
    if (ours != null && before != null && player.getScoreboard() == ours)
      player.setScoreboard(before);
  }

  public void forget(UUID id) {
    owned.remove(id);
    previous.remove(id);
  }

  public void close() {
    for (Player player : server.getOnlinePlayers()) restore(player);
    owned.clear();
    previous.clear();
  }
}
