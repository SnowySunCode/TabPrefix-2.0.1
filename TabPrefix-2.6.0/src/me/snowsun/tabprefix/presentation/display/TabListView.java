package me.snowsun.tabprefix.presentation.display;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import me.snowsun.tabprefix.domain.MinecraftVersion;
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
  private final boolean legacyLimit;

  public TabListView() {
    this(MinecraftVersion.parse("1.16.5"));
  }

  public TabListView(MinecraftVersion version) {
    legacyLimit = version.compareTo(MinecraftVersion.parse("1.13")) < 0;
  }

  public void apply(Player player, String prefix) {
    if (prefix.isEmpty()) {
      restore(player);
      return;
    }
    applyName(player, prefix + ChatColor.RESET + player.getName());
  }

  public String original(Player player) {
    NameState state = names.get(player.getUniqueId());
    return state == null ? player.getPlayerListName() : state.original;
  }

  public void applyName(Player player, String rendered) {
    if (legacyLimit && rendered.length() > 16) rendered = player.getName();
    NameState state = names.get(player.getUniqueId());
    if (state == null || !java.util.Objects.equals(player.getPlayerListName(), state.applied)) {
      state = new NameState(player.getPlayerListName());
      names.put(player.getUniqueId(), state);
    }
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
