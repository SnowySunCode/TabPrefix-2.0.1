package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

/** One owner is required for native title/subtitle/action-bar channels. */
public final class ScreenHudView implements AutoCloseable {
  private static final class State {
    final Player player;
    String title = "", sub = "", action = "";
    long titleTick, actionTick;

    State(Player p) {
      player = p;
    }
  }

  private final Map<UUID, State> states = new HashMap<>();

  public void render(Player p, String title, String sub, String action, long tick) {
    State s = states.get(p.getUniqueId());
    if (s == null) {
      if (title.isEmpty() && sub.isEmpty() && action.isEmpty()) return;
      s = new State(p);
      states.put(p.getUniqueId(), s);
    }
    boolean changed = !s.title.equals(title) || !s.sub.equals(sub);
    if (title.isEmpty() && sub.isEmpty()) {
      if (changed) p.resetTitle();
    } else if (changed || tick - s.titleTick >= 40) {
      p.sendTitle(title, sub, 0, 80, 0);
      s.titleTick = tick;
    }
    s.title = title;
    s.sub = sub;
    if (!s.action.equals(action) || !action.isEmpty() && tick - s.actionTick >= 30) {
      p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(action));
      s.actionTick = tick;
    }
    s.action = action;
  }

  public void forget(UUID id) {
    states.remove(id);
  }

  public void close() {
    for (State s : new ArrayList<>(states.values()))
      if (s.player.isOnline()) render(s.player, "", "", "", 0);
    states.clear();
  }
}
