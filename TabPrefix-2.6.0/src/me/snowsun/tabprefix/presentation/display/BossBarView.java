package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;
import me.snowsun.tabprefix.domain.DisplayDesign;
import me.snowsun.tabprefix.domain.PlayerDisplaySettings;
import org.bukkit.Server;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;

/** Owns only the unkeyed bars created here. Never changes another plugin's bars. */
public final class BossBarView implements AutoCloseable {
  public static final class Frame {
    public final DisplayDesign.Boss design;
    public final String title;
    public final double progress;
    public String reason;
    public boolean active;

    private Frame(DisplayDesign.Boss design, String title, double progress, String reason) {
      this.design = design;
      this.title = title;
      this.progress = progress;
      this.reason = reason;
    }
  }

  private static final class State {
    long started;
    int revision;
    final Map<String, BossBar> bars = new LinkedHashMap<>();
    final Map<String, Long> clocks = new HashMap<>();

    State(long tick, int revision) {
      started = tick;
      this.revision = revision;
    }
  }

  private final Server server;
  private final Map<UUID, State> states = new HashMap<>();

  public BossBarView(Server server) {
    this.server = server;
  }

  public static List<Frame> frames(
      Player viewer,
      DisplayDesign design,
      PlayerDisplaySettings prefs,
      Map<String, String> vars,
      long elapsed,
      BiFunction<DisplayDesign.Line, Map<String, String>, String> text) {
    return frames(viewer, design, prefs, vars, elapsed, bar -> elapsed, text);
  }

  private static List<Frame> frames(
      Player viewer,
      DisplayDesign design,
      PlayerDisplaySettings prefs,
      Map<String, String> vars,
      long elapsed,
      ToLongFunction<DisplayDesign.Boss> clock,
      BiFunction<DisplayDesign.Line, Map<String, String>, String> text) {
    long time = prefs.animations ? Math.max(0, elapsed) : 0;
    List<Frame> frames = new ArrayList<>(), eligible = new ArrayList<>();
    for (DisplayDesign.Boss bar : design.bossBars) {
      long barTime = prefs.animations ? Math.max(0, clock.applyAsLong(bar)) : 0;
      double progress = BossBarProgress.value(bar, vars, barTime);
      Map<String, String> values = new LinkedHashMap<>(vars);
      values.put(
          "progress", String.valueOf(Math.round(progress * 1000) / 10d).replaceAll("\\.0$", ""));
      values.put("remaining", String.valueOf(BossBarProgress.remaining(bar, barTime)));
      String reason =
          !bar.enabled
              ? "DISABLED"
              : !bar.worlds.isEmpty() && !bar.worlds.contains(viewer.getWorld().getName())
                  ? "WORLD"
                  : !bar.permission.isEmpty() && !viewer.hasPermission(bar.permission)
                      ? "PERMISSION"
                      : bar.timed() && !bar.loop && bar.hideAfter && barTime >= bar.durationTicks
                          ? "COMPLETED"
                          : "";
      Frame frame = new Frame(bar, text.apply(bar.line, values), progress, reason);
      frames.add(frame);
      if (reason.isEmpty()) eligible.add(frame);
    }
    int index =
        eligible.isEmpty() ? 0 : (int) ((time / design.bossBarRotateTicks) % eligible.size());
    for (Frame frame : eligible) {
      frame.reason =
          !design.bossBarsEnabled
              ? "SERVER_DISABLED"
              : !prefs.bossBars
                  ? "PERSONAL_DISABLED"
                  : design.bossBarMode.equals("ROTATE") && eligible.get(index) != frame
                      ? "ROTATION"
                      : "";
      frame.active = frame.reason.isEmpty();
    }
    return frames;
  }

  public void render(
      Player viewer,
      DisplayDesign design,
      PlayerDisplaySettings prefs,
      Map<String, String> vars,
      long tick,
      int revision,
      BiFunction<DisplayDesign.Line, Map<String, String>, String> text) {
    UUID id = viewer.getUniqueId();
    if (!design.bossBarsEnabled || !prefs.bossBars || design.bossBars.isEmpty()) {
      forget(id);
      return;
    }
    State state = states.computeIfAbsent(id, ignored -> new State(tick, revision));
    if (state.revision != revision) {
      state.started = tick;
      state.revision = revision;
      state.clocks.clear();
    }
    List<Frame> wanted = new ArrayList<>();
    List<String> order = new ArrayList<>();
    for (Frame frame :
        frames(
            viewer,
            design,
            prefs,
            vars,
            tick - state.started,
            bar -> {
              if (!bar.enabled
                  || !bar.worlds.isEmpty() && !bar.worlds.contains(viewer.getWorld().getName())
                  || !bar.permission.isEmpty() && !viewer.hasPermission(bar.permission)) {
                state.clocks.remove(bar.id);
                return 0;
              }
              return tick - state.clocks.computeIfAbsent(bar.id, ignored -> tick);
            },
            text)) {
      if (frame.active) {
        wanted.add(frame);
        order.add(frame.design.id);
      }
    }
    boolean reordered = !new ArrayList<>(state.bars.keySet()).equals(order);
    if (reordered) {
      for (BossBar bar : state.bars.values()) bar.removeAll();
      state.bars.keySet().retainAll(order);
    }
    Map<String, BossBar> next = new LinkedHashMap<>();
    for (Frame frame : wanted) {
      DisplayDesign.Boss d = frame.design;
      BossBar bar = state.bars.get(d.id);
      if (bar == null)
        bar =
            server.createBossBar(frame.title, BarColor.valueOf(d.color), BarStyle.valueOf(d.style));
      if (!Objects.equals(bar.getTitle(), frame.title)) bar.setTitle(frame.title);
      if (bar.getColor() != BarColor.valueOf(d.color)) bar.setColor(BarColor.valueOf(d.color));
      if (bar.getStyle() != BarStyle.valueOf(d.style)) bar.setStyle(BarStyle.valueOf(d.style));
      if (Double.compare(bar.getProgress(), frame.progress) != 0) bar.setProgress(frame.progress);
      flag(bar, BarFlag.DARKEN_SKY, d.darkenSky);
      flag(bar, BarFlag.PLAY_BOSS_MUSIC, d.playMusic);
      flag(bar, BarFlag.CREATE_FOG, d.createFog);
      if (!bar.isVisible()) bar.setVisible(true);
      if (reordered || !bar.getPlayers().contains(viewer)) bar.addPlayer(viewer);
      next.put(d.id, bar);
    }
    state.bars.clear();
    state.bars.putAll(next);
  }

  private static void flag(BossBar bar, BarFlag flag, boolean enabled) {
    if (enabled && !bar.hasFlag(flag)) bar.addFlag(flag);
    else if (!enabled && bar.hasFlag(flag)) bar.removeFlag(flag);
  }

  public void forget(UUID viewer) {
    State state = states.remove(viewer);
    if (state != null) for (BossBar bar : state.bars.values()) bar.removeAll();
  }

  public int count() {
    int count = 0;
    for (State state : states.values()) count += state.bars.size();
    return count;
  }

  public void close() {
    for (UUID id : new ArrayList<>(states.keySet())) forget(id);
  }
}
