package org.bukkit.event.player;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** Independent surface fixture for the modern pack ID and terminal status API. */
public final class PlayerResourcePackStatusEvent extends PlayerEvent {
  public enum Status {
    SUCCESSFULLY_LOADED,
    DECLINED,
    FAILED_DOWNLOAD,
    ACCEPTED,
    DOWNLOADED,
    INVALID_URL,
    FAILED_RELOAD,
    DISCARDED
  }

  private final UUID id;
  private final Status status;
  private static final HandlerList HANDLERS = new HandlerList();

  public PlayerResourcePackStatusEvent(Player player, UUID id, Status status) {
    super(player);
    this.id = id;
    this.status = status;
  }

  public UUID getID() {
    return id;
  }

  public Status getStatus() {
    return status;
  }

  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
