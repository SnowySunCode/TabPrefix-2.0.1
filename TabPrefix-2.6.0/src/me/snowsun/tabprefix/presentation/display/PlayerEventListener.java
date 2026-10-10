package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import me.snowsun.tabprefix.application.port.MainThreadExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerEventListener implements Listener {
  private final PrefixController controller;
  private final MainThreadExecutor main;
  private final JavaPlugin plugin;

  public PlayerEventListener(
      PrefixController controller, MainThreadExecutor main, JavaPlugin plugin) {
    this.controller = controller;
    this.main = main;
    this.plugin = plugin;
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    controller.watch(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    controller.forget(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void world(PlayerChangedWorldEvent e) {
    controller.request(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    controller.request(e.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void chat(AsyncPlayerChatEvent e) {
    ChatPrefix prefix = controller.chat(e.getPlayer().getUniqueId());
    if (prefix.asset == null) {
      if (!prefix.text.isEmpty()) e.setFormat(ChatFormat.prepend(prefix.text, e.getFormat()));
      return;
    }
    e.setCancelled(true);
    String format = e.getFormat(), message = e.getMessage(), name = e.getPlayer().getDisplayName();
    Set<Player> recipients = new HashSet<>(e.getRecipients());
    main.execute(
        () -> {
          for (Player recipient : recipients)
            if (recipient.isOnline())
              recipient.sendMessage(
                  controller.forViewer(prefix, recipient.getUniqueId())
                      + render(format, name, message));
          plugin
              .getServer()
              .getConsoleSender()
              .sendMessage(prefix.text + render(format, name, message));
        });
  }

  private static String render(String format, String name, String message) {
    try {
      return String.format(format, name, message);
    } catch (IllegalFormatException error) {
      return "<" + name + "> " + message;
    }
  }
}
