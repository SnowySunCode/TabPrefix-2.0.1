package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.*;

/** Stable score entries keep equal and blank lines; only owned objectives/teams are removed. */
public final class SidebarView implements AutoCloseable {
  private static final String OBJECTIVE = "tp_sidebar";

  private static final class State {
    final Objective objective;
    final Map<Integer, Team> teams = new HashMap<>();
    final String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    State(Objective objective) {
      this.objective = objective;
    }
  }

  private final Map<Scoreboard, State> states = new IdentityHashMap<>();
  private final int titleLimit, textLimit;
  private final me.snowsun.tabprefix.infrastructure.bukkit.SidebarNumbers numbers;

  public SidebarView() {
    this(MinecraftVersion.parse("1.16.5"));
  }

  public SidebarView(MinecraftVersion version) {
    numbers = new me.snowsun.tabprefix.infrastructure.bukkit.SidebarNumbers(version);
    titleLimit = version.objectiveTitleLimit();
    textLimit = version.teamTextLimit();
  }

  public boolean render(Scoreboard board, String title, List<String> lines) {
    State state = states.get(board);
    Objective active = board.getObjective(DisplaySlot.SIDEBAR);
    if (active != null && (state == null || !same(active, state.objective))) {
      clear(board);
      return false;
    }
    if (state != null && !same(board.getObjective(OBJECTIVE), state.objective)) {
      clear(board);
      state = null;
    }
    boolean created = state == null;
    if (state == null) {
      if (board.getObjective(OBJECTIVE) != null) return false;
      // The two-argument overload exists on every supported Bukkit API, including 1.12.
      state = new State(board.registerNewObjective(OBJECTIVE, "dummy"));
      state.objective.setDisplayName(NameTagView.truncate(title, titleLimit));
      states.put(board, state);
      numbers.hide(state.objective);
    }
    String clipped = NameTagView.truncate(title, titleLimit);
    if (!state.objective.getDisplayName().equals(clipped)) state.objective.setDisplayName(clipped);
    int count = Math.min(15, lines.size());
    for (int i = 0; i < count; i++) {
      String entry = entry(state, i);
      Team team = state.teams.get(i);
      if (team == null) {
        String name = "tpsb" + i;
        if (board.getTeam(name) != null) {
          clear(board);
          return false;
        }
        team = board.registerNewTeam(name);
        team.addEntry(entry);
        state.teams.put(i, team);
      }
      String line = lines.get(i), start = NameTagView.truncate(line, textLimit);
      String tail =
          NameTagView.truncate(
              ChatColor.getLastColors(start) + line.substring(start.length()), textLimit);
      if (!team.getPrefix().equals(start)) team.setPrefix(start);
      if (!team.getSuffix().equals(tail)) team.setSuffix(tail);
      if (state.objective.getScore(entry).getScore() != 15 - i)
        state.objective.getScore(entry).setScore(15 - i);
    }
    for (Iterator<Map.Entry<Integer, Team>> it = state.teams.entrySet().iterator();
        it.hasNext(); ) {
      Map.Entry<Integer, Team> e = it.next();
      if (e.getKey() >= count) {
        board.resetScores(entry(state, e.getKey()));
        unregister(e.getValue());
        it.remove();
      }
    }
    if (created) state.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    return true;
  }

  public String numberStatus() {
    return numbers.status();
  }

  private static boolean same(Objective a, Objective b) {
    if (a == b) return true;
    if (a == null || b == null || a.getName() == null || !a.getName().equals(b.getName()))
      return false;
    try {
      return a.getClass().getMethod("getHandle").invoke(a)
          == b.getClass().getMethod("getHandle").invoke(b);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return Objects.equals(a.getScoreboard(), b.getScoreboard());
    }
  }

  private static String entry(State state, int index) {
    StringBuilder b = new StringBuilder();
    for (char c : state.token.toCharArray()) b.append('§').append(c);
    return b.append('§').append(Integer.toHexString(index)).append("§r").toString();
  }

  public void retain(Set<Scoreboard> boards) {
    for (Scoreboard b : new ArrayList<>(states.keySet())) if (!boards.contains(b)) clear(b);
  }

  public void clear(Scoreboard board) {
    State state = states.remove(board);
    if (state == null) return;
    for (Team team : state.teams.values()) unregister(team);
    try {
      if (same(board.getObjective(OBJECTIVE), state.objective)) state.objective.unregister();
    } catch (IllegalStateException ignored) {
    }
  }

  private static void unregister(Team team) {
    try {
      team.unregister();
    } catch (IllegalStateException ignored) {
    }
  }

  public void close() {
    for (Scoreboard board : new ArrayList<>(states.keySet())) clear(board);
  }
}
