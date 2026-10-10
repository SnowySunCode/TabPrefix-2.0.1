package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import java.util.function.*;
import me.snowsun.tabprefix.domain.DisplayDesign;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import org.bukkit.entity.Player;

/** Shared deterministic sorting and suffix composition; no permission or database reads. */
public final class TabFormatting {
  private TabFormatting() {}

  public static Comparator<Player> order(
      DisplayDesign d,
      Function<Player, String> group,
      ToIntFunction<Player> weight,
      ToIntFunction<Player> ping) {
    Comparator<Player> names =
        Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Player::getUniqueId);
    Comparator<Player> groups = Comparator.comparing(group, String.CASE_INSENSITIVE_ORDER);
    Comparator<Player> comparator;
    switch (d.sort) {
      case "GROUP":
        comparator = groups.thenComparing(names);
        break;
      case "PRIORITY":
        comparator =
            Comparator.comparingInt((Player p) -> d.groupRank(group.apply(p)))
                .thenComparing(Comparator.comparingInt(weight).reversed())
                .thenComparing(groups)
                .thenComparing(names);
        break;
      case "WEIGHT":
        comparator =
            Comparator.comparingInt(weight).reversed().thenComparing(groups).thenComparing(names);
        break;
      case "PING":
        comparator = Comparator.comparingInt(ping).thenComparing(names);
        break;
      case "WORLD":
        comparator =
            Comparator.comparing(
                    (Player p) -> p.getWorld().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(names);
        break;
      default:
        comparator = names;
    }
    return d.sortReverse ? comparator.reversed() : comparator;
  }

  public static String line(
      DisplayDesign.Line line,
      Map<String, String> vars,
      TextRenderer renderer,
      long tick,
      boolean animate) {
    String legacy = renderer.legacy(renderer.display(line.source(tick, animate), vars));
    return animate && line.animation.equals("SCROLL")
        ? DisplayAnimation.scroll(legacy, line.width, line.gap, tick / line.speed)
        : legacy;
  }

  public static String suffix(
      DisplayDesign d,
      DisplayDesign.GroupStyle style,
      String luckSuffix,
      Map<String, String> vars,
      TextRenderer renderer,
      long tick,
      boolean enabled,
      boolean animate) {
    if (!enabled || !d.suffixEnabled) return "";
    String suffix =
        style == null
            ? (d.luckSuffixFallback ? luckSuffix : "")
            : style.suffixMode.equals("NONE")
                ? ""
                : style.suffixMode.equals("LUCKPERMS")
                    ? luckSuffix
                    : line(style.suffix, vars, renderer, tick, animate);
    String visible = suffix.replaceAll("§[0-9a-fklmnorxA-FKLMNORX]", "");
    if (visible.isEmpty()) return "";
    return "§r" + (Character.isWhitespace(visible.charAt(0)) ? "" : " ") + suffix;
  }

  public static String name(String player, DisplayDesign.GroupStyle style, TextRenderer renderer) {
    if (style == null || style.nameColor.equals("DEFAULT")) return player;
    return renderer.legacy(
        renderer.display(
            "<" + style.nameColor + ">{player}</" + style.nameColor + ">",
            Collections.singletonMap("player", player)));
  }
}
