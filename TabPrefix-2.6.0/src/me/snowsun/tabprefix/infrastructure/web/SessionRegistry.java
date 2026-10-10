package me.snowsun.tabprefix.infrastructure.web;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;
import me.snowsun.tabprefix.config.FeatureSettings;
import me.snowsun.tabprefix.domain.GroupTextPrefix;
import me.snowsun.tabprefix.util.Digests;

public final class SessionRegistry {
  public static final class Opened {
    public final EditorSession session;
    public final String token;

    Opened(EditorSession session, String token) {
      this.session = session;
      this.token = token;
    }
  }

  private final Map<String, EditorSession> sessions = new HashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final LongSupplier clock;
  private volatile FeatureSettings settings;

  public SessionRegistry(FeatureSettings settings) {
    this(settings, System::currentTimeMillis);
  }

  public SessionRegistry(FeatureSettings settings, LongSupplier clock) {
    this.settings = settings;
    this.clock = clock;
  }

  public void reload(FeatureSettings settings) {
    this.settings = settings;
  }

  public synchronized Opened open(UUID owner, String player, String group) {
    return open(owner, player, group, null);
  }

  public synchronized Opened open(UUID owner, String player, String group, String origin) {
    expire();
    for (EditorSession session : sessions.values())
      if (session.owner.equals(owner)) session.revoked = true;
    expire();
    if (sessions.size() >= settings.maxSessions)
      throw new IllegalArgumentException("Editor session limit reached.");
    byte[] randomBytes = new byte[32];
    random.nextBytes(randomBytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    EditorSession session =
        new EditorSession(
            owner,
            player,
            GroupTextPrefix.normalizeGroup(group),
            clock.getAsLong() + settings.sessionMinutes * 60000L,
            origin);
    sessions.put(Digests.sha256(token), session);
    return new Opened(session, token);
  }

  public synchronized EditorSession require(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new HttpProblem(401, "Editor session expired. Open a new editor in Minecraft.");
    EditorSession session = sessions.get(Digests.sha256(token));
    if (session == null || session.revoked || session.expiresAt <= clock.getAsLong()) {
      if (session != null) session.revoked = true;
      throw new HttpProblem(401, "Editor session expired. Open a new editor in Minecraft.");
    }
    return session;
  }

  public synchronized Set<UUID> active() {
    expire();
    Set<UUID> ids = new HashSet<>();
    for (EditorSession session : sessions.values()) ids.add(session.id);
    return ids;
  }

  public synchronized int count() {
    expire();
    return sessions.size();
  }

  public synchronized void close() {
    for (EditorSession session : sessions.values()) session.revoked = true;
    sessions.clear();
  }

  private void expire() {
    long now = clock.getAsLong();
    sessions
        .values()
        .removeIf(
            s -> {
              if (s.revoked || s.expiresAt <= now) {
                s.revoked = true;
                return true;
              }
              return false;
            });
  }
}
