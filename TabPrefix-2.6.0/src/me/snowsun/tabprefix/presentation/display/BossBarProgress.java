package me.snowsun.tabprefix.presentation.display;

import java.util.Map;
import me.snowsun.tabprefix.domain.DisplayDesign;

/** Shared deterministic fill and timer calculation for Minecraft and the read-only preview. */
public final class BossBarProgress {
  private BossBarProgress() {}

  public static double value(DisplayDesign.Boss bar, Map<String, String> vars, long tick) {
    double progress;
    switch (bar.progressMode) {
      case "HEALTH":
        progress = number(vars, "health") / Math.max(0.01, number(vars, "max_health"));
        break;
      case "FOOD":
        progress = number(vars, "food") / 20;
        break;
      case "EXPERIENCE":
        progress = number(vars, "experience") / 100;
        break;
      case "ONLINE":
        progress = number(vars, "online") / Math.max(1, number(vars, "max"));
        break;
      case "CUSTOM":
        progress = number(vars, bar.progressSource) / bar.progressMax;
        break;
      case "FILL":
        progress = phase(bar, tick);
        break;
      case "DRAIN":
        progress = 1 - phase(bar, tick);
        break;
      case "PULSE":
        progress = 1 - Math.abs(2 * phase(bar, tick) - 1);
        break;
      default:
        progress = bar.progress / 100;
    }
    return Double.isFinite(progress) ? Math.max(0, Math.min(1, progress)) : 0;
  }

  private static double phase(DisplayDesign.Boss bar, long tick) {
    long elapsed = Math.max(0, tick);
    return (bar.loop ? elapsed % bar.durationTicks : Math.min(elapsed, bar.durationTicks))
        / (double) bar.durationTicks;
  }

  public static long remaining(DisplayDesign.Boss bar, long tick) {
    if (!bar.timed()) return 0;
    long elapsed = Math.max(0, tick);
    long rest =
        bar.loop
            ? bar.durationTicks - elapsed % bar.durationTicks
            : Math.max(0, bar.durationTicks - elapsed);
    return (rest + 19) / 20;
  }

  private static double number(Map<String, String> vars, String key) {
    try {
      double n = Double.parseDouble(vars.getOrDefault(key, "0"));
      return Double.isFinite(n) ? n : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
