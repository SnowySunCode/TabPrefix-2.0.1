package me.snowsun.tabprefix.presentation.display;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/** Restores only names that are still owned by this adapter. Main-thread only. */
public final class TabListView {
  private static final class NameState {
    final String original;
    String applied;

    NameState(String original) {
      this.original = original;
    }
  }

  private final Map<UUID, NameState> names = new HashMap<>();

  public void apply(Player player, String prefix) {
    if (prefix.isEmpty()) {
      restore(player);
      return;
    }
    NameState state = names.get(player.getUniqueId());
    if (state == null || !java.util.Objects.equals(player.getPlayerListName(), state.applied)) {
      state = new NameState(player.getPlayerListName());
      names.put(player.getUniqueId(), state);
    }
    String rendered = prefix + ChatColor.RESET + player.getName();
    if (!rendered.equals(player.getPlayerListName())) player.setPlayerListName(rendered);
    state.applied = rendered;
  }

  public void restore(Player player) {
    NameState state = names.remove(player.getUniqueId());
    if (state != null && java.util.Objects.equals(player.getPlayerListName(), state.applied)) {
      player.setPlayerListName(state.original);
    }
  }

  public void forget(UUID player) {
    names.remove(player);
  }
}
