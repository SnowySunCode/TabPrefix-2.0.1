package me.snowsun.tabprefix.infrastructure.web;

import java.net.*;
import java.util.*;
import java.util.function.*;
import me.snowsun.tabprefix.config.FeatureSettings;
import me.snowsun.tabprefix.domain.PackRevision;

/** Shared actual HTTP endpoint, LAN discovery and bounded, temporary player choices. */
public final class WebAddresses {
  private final FeatureSettings settings;
  private final String serverIp, fixedBase;
  private final Supplier<List<LocalNetwork.Address>> discovery;
  private final LongSupplier clock;
  private final Map<UUID, Choice> choices = new LinkedHashMap<>();
  private volatile InetAddress binding;
  private volatile int port;
  private volatile List<LocalNetwork.Address> snapshot = Collections.emptyList();
  private long scannedAt = Long.MIN_VALUE;

  private static final class Choice {
    final String host;
    final long expires;

    Choice(String host, long expires) {
      this.host = host;
      this.expires = expires;
    }
  }

  public WebAddresses(FeatureSettings settings, String serverIp) {
    this(settings, serverIp, LocalNetwork::scan, System::currentTimeMillis, null);
  }

  public WebAddresses(
      FeatureSettings settings,
      String serverIp,
      Supplier<List<LocalNetwork.Address>> discovery,
      LongSupplier clock) {
    this(settings, serverIp, discovery, clock, null);
  }

  private WebAddresses(
      FeatureSettings settings,
      String serverIp,
      Supplier<List<LocalNetwork.Address>> discovery,
      LongSupplier clock,
      String fixedBase) {
    this.settings = settings;
    this.serverIp = serverIp == null ? "" : serverIp;
    this.discovery = discovery;
    this.clock = clock;
    this.fixedBase = fixedBase;
    port = settings.port;
  }

  /** Compatibility for callers supplying a complete, explicitly chosen base URL. */
  public static WebAddresses fixed(FeatureSettings settings, String base) {
    return new WebAddresses(settings, "", LocalNetwork::scan, System::currentTimeMillis, base);
  }

  public synchronized void bound(InetSocketAddress actual) {
    binding = actual.getAddress();
    port = actual.getPort();
    scannedAt = Long.MIN_VALUE;
  }

  public int port() {
    return port;
  }

  public boolean automaticPort() {
    // A proxy/NAT/external pack URL may depend on the configured fixed port.
    return settings.autoPort
        && settings.publicHost.isEmpty()
        && settings.editorBase.isEmpty()
        && settings.packBase.isEmpty()
        && fixedBase == null;
  }

  public synchronized List<LocalNetwork.Address> available() {
    long now = clock.getAsLong();
    if (scannedAt == Long.MIN_VALUE || now - scannedAt >= 3000 || now < scannedAt) {
      List<LocalNetwork.Address> filtered = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (LocalNetwork.Address a : discovery.get()) {
        if (binding != null && !binding.isAnyLocalAddress() && !binding.equals(a.ip)) continue;
        if (seen.add(a.host())) filtered.add(a);
      }
      snapshot = Collections.unmodifiableList(filtered);
      scannedAt = now;
    }
    return snapshot;
  }

  public String localBase(LocalNetwork.Address a) {
    return http(a.host(), port);
  }

  public synchronized boolean select(UUID owner, String host) {
    if (available().stream().noneMatch(a -> a.host().equals(host))) return false;
    expireChoices();
    choices.remove(owner);
    if (choices.size() >= 512) choices.remove(choices.keySet().iterator().next());
    choices.put(owner, new Choice(host, clock.getAsLong() + 2 * 60 * 60 * 1000L));
    return true;
  }

  public synchronized void automatic(UUID owner) {
    choices.remove(owner);
  }

  public synchronized String base(UUID owner, InetAddress peer) {
    expireChoices();
    Choice chosen = choices.get(owner);
    if (chosen != null) {
      for (LocalNetwork.Address a : available())
        if (a.host().equals(chosen.host)) return localBase(a);
      choices.remove(owner);
    }
    if (fixedBase != null) {
      URI fixed = URI.create(fixedBase);
      // Older callers can still supply a local URL before an ephemeral port is assigned.
      if (fixed.getPort() == 0 && port > 0) {
        String authority = fixed.getRawAuthority();
        String prefix = fixed.getScheme() + "://" + authority;
        return fixed.getScheme()
            + "://"
            + authority.substring(0, authority.length() - 1)
            + port
            + fixedBase.substring(prefix.length());
      }
      return fixedBase;
    }
    if (!settings.editorBase.isEmpty()) return settings.editorBase;
    if (!settings.publicHost.isEmpty()) return http(settings.publicHost, port);
    LocalNetwork.Address best = LocalNetwork.best(available(), peer, serverIp);
    return best == null
        ? http(
            binding != null && !binding.isAnyLocalAddress()
                ? binding.getHostAddress()
                : "127.0.0.1",
            port)
        : localBase(best);
  }

  public String packUrl(UUID owner, InetAddress peer, PackRevision pack) {
    String base = settings.packBase.isEmpty() ? base(owner, peer) : settings.packBase;
    return base
        + "/packs/"
        + (settings.versioned
            ? "TabPrefix-" + pack.hash + ".zip"
            : "TabPrefix.zip?hash=" + pack.hash);
  }

  public synchronized void clear() {
    choices.clear();
  }

  private void expireChoices() {
    long now = clock.getAsLong();
    choices.values().removeIf(c -> c.expires <= now);
  }

  public static String http(String host, int port) {
    String formatted = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    return "http://" + formatted + ":" + port;
  }

  public static String origin(String base) {
    URI uri = URI.create(base);
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }
}
