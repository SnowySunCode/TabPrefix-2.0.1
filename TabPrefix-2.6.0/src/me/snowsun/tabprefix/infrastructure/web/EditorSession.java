package me.snowsun.tabprefix.infrastructure.web;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.snowsun.tabprefix.infrastructure.media.ProcessedMedia;

public final class EditorSession {
  public final UUID id = UUID.randomUUID(), owner;
  public final String player, group, origin;
  public final long expiresAt;
  public final AtomicBoolean busy = new AtomicBoolean();
  public volatile boolean revoked;
  public volatile boolean prefixes = true, design, preferences;
  public volatile Path upload;
  public volatile ProcessedMedia preview;
  public volatile String uploadFormat;
  private long window = System.currentTimeMillis();
  private int requests, mutations;

  EditorSession(UUID owner, String player, String group, long expiresAt, String origin) {
    this.owner = owner;
    this.player = player;
    this.group = group;
    this.expiresAt = expiresAt;
    this.origin = origin;
  }

  synchronized boolean permit(long now, boolean mutation) {
    if (now - window >= 60000) {
      window = now;
      requests = 0;
      mutations = 0;
    }
    return ++requests <= 600 && (!mutation || ++mutations <= 30);
  }
}
