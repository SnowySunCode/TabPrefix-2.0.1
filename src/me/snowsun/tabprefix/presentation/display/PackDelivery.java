package me.snowsun.tabprefix.presentation.display;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import me.snowsun.tabprefix.application.port.AuditSink;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.ResourcePackService;
import me.snowsun.tabprefix.presentation.message.MessageService;
import me.snowsun.tabprefix.util.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class PackDelivery implements Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final ResourcePackService packs;
  private final MessageService messages;
  private final AuditSink audit;
  private final Map<UUID, PackRequestState> states = new ConcurrentHashMap<>();
  private volatile PluginSettings settings;
  private Consumer<UUID> changed = id -> {};
  private BukkitTask timeout;

  public PackDelivery(
      JavaPlugin plugin,
      ResourcePackService packs,
      MessageService messages,
      AuditSink audit,
      PluginSettings settings) {
    this.plugin = plugin;
    this.packs = packs;
    this.messages = messages;
    this.audit = audit;
    this.settings = settings;
  }

  public void onChanged(Consumer<UUID> listener) {
    changed = listener;
  }

  public void reload(PluginSettings settings) {
    this.settings = settings;
  }

  public void start() {
    timeout =
        plugin
            .getServer()
            .getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  long now = System.currentTimeMillis();
                  for (Map.Entry<UUID, PackRequestState> e : states.entrySet())
                    if (e.getValue().timeout(now)) {
                      changed.accept(e.getKey());
                      Player p = plugin.getServer().getPlayer(e.getKey());
                      if (p != null)
                        audit.record(
                            "pack-load-failed",
                            Values.of(
                                "player",
                                p.getName(),
                                "reason",
                                "response timeout; reconnect to retry"));
                    }
                },
                100,
                100);
    if (settings.features.autoSend) sendAll(false);
  }

  public boolean has(UUID viewer, UUID asset) {
    if (!settings.features.pack) return false;
    PackRequestState state = states.get(viewer);
    PackRevision loaded = state == null ? null : state.loaded();
    return loaded != null && loaded.assets.contains(asset);
  }

  public boolean pending(UUID viewer) {
    PackRequestState state = states.get(viewer);
    return state != null && state.pending();
  }

  public int sendAll(boolean manual) {
    int count = 0;
    PackRevision revision = packs.active();
    if (revision == null || !manual && revision.glyphs == 0) return 0;
    for (Player p : plugin.getServer().getOnlinePlayers())
      if (send(p, revision) == PackRequestState.Offer.SEND) count++;
    audit.record("pack-sent", Values.of("players", count));
    return count;
  }

  public PackRequestState.Offer send(Player player, PackRevision revision) {
    if (!settings.features.pack || revision == null || !player.isOnline())
      return PackRequestState.Offer.SAME;
    PackRequestState state =
        states.computeIfAbsent(player.getUniqueId(), id -> new PackRequestState());
    PackRequestState.Offer offer = state.offer(revision, System.currentTimeMillis());
    if (offer == PackRequestState.Offer.SEND) {
      packs.pin(revision);
      try {
        player.setResourcePack(revision.url, Digests.unhex(revision.hash));
      } catch (RuntimeException error) {
        state.terminal(false);
        changed.accept(player.getUniqueId());
        throw error;
      }
    }
    return offer;
  }

  public void published() {
    if (settings.features.autoSend && settings.features.resend) sendAll(false);
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    if (settings.features.autoSend && settings.features.sendOnJoin)
      plugin
          .getServer()
          .getScheduler()
          .runTaskLater(
              plugin,
              () -> {
                PackRevision active = packs.active();
                if (active != null && active.glyphs > 0) send(event.getPlayer(), active);
              },
              10);
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    states.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void status(PlayerResourcePackStatusEvent event) {
    PackRequestState state = states.get(event.getPlayer().getUniqueId());
    if (state == null || !state.pending()) return;
    switch (event.getStatus()) {
      case ACCEPTED:
        messages.send(event.getPlayer(), "resource-pack.accepted");
        break;
      case SUCCESSFULLY_LOADED:
        PackRevision next = state.terminal(true);
        changed.accept(event.getPlayer().getUniqueId());
        messages.send(event.getPlayer(), "resource-pack.loaded");
        if (next != null) send(event.getPlayer(), next);
        break;
      case DECLINED:
      case FAILED_DOWNLOAD:
        state.terminal(false);
        changed.accept(event.getPlayer().getUniqueId());
        String key =
            event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED
                ? "resource-pack.declined"
                : "resource-pack.failed-download";
        messages.send(event.getPlayer(), key);
        audit.record(
            "pack-load-failed",
            Values.of("player", event.getPlayer().getName(), "reason", event.getStatus()));
        break;
      default:
        break;
    }
  }

  @Override
  public void close() {
    if (timeout != null) timeout.cancel();
    states.clear();
  }
}
