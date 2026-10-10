package me.snowsun.tabprefix.infrastructure.web;

import java.net.*;
import java.util.*;

/** Enumerates addresses owned by the server; an interface name is not a Wi-Fi SSID. */
public final class LocalNetwork {
  private LocalNetwork() {}

  public static final class Address {
    public final String name, label;
    public final InetAddress ip;
    public final int prefix;
    public final boolean virtual;

    public Address(String name, String label, InetAddress ip, int prefix, boolean virtual) {
      if (ip == null || ip.isAnyLocalAddress() || ip.isMulticastAddress())
        throw new IllegalArgumentException("Expected a local unicast address.");
      if (prefix < -1 || prefix > ip.getAddress().length * 8)
        throw new IllegalArgumentException("Invalid network prefix.");
      this.name = clean(name);
      this.label = clean(label == null ? name : label);
      this.ip = ip;
      this.prefix = prefix;
      this.virtual = virtual;
    }

    public String host() {
      return ip.getHostAddress();
    }

    public boolean contains(InetAddress peer) {
      if (peer == null || peer.getAddress().length != ip.getAddress().length) return false;
      byte[] a = ip.getAddress(), b = peer.getAddress();
      // A missing mask permits an exact host match, never an entire network.
      int bits = prefix < 0 ? a.length * 8 : prefix;
      for (int i = 0; bits > 0; i++, bits -= 8) {
        int mask = 0xff << (8 - Math.min(bits, 8));
        if ((a[i] & mask) != (b[i] & mask)) return false;
      }
      return true;
    }
  }

  public static List<Address> scan() {
    List<Address> found = new ArrayList<>();
    try {
      Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
      if (interfaces != null)
        while (interfaces.hasMoreElements()) {
          NetworkInterface network = interfaces.nextElement();
          try {
            if (!network.isUp()) continue;
            boolean virtual = network.isVirtual() || virtualName(network);
            for (InterfaceAddress entry : network.getInterfaceAddresses()) {
              InetAddress ip = entry.getAddress();
              if (ip == null || ip.isAnyLocalAddress() || ip.isMulticastAddress()) continue;
              // A scoped IPv6 address is not portable to a browser on another machine.
              if (ip instanceof Inet6Address && ip.isLinkLocalAddress()) continue;
              found.add(
                  new Address(
                      network.getName(),
                      network.getDisplayName(),
                      ip,
                      entry.getNetworkPrefixLength(),
                      virtual));
            }
          } catch (SocketException | SecurityException ignored) {
            // A single inaccessible/disappearing interface must not hide the remaining ones.
          }
        }
    } catch (SocketException | SecurityException ignored) {
      // Loopback remains useful for an editor opened on the server computer itself.
    }
    if (found.stream().noneMatch(a -> a.ip.isLoopbackAddress()))
      found.add(new Address("loopback", "localhost", loopback(), 8, false));
    found.sort(Comparator.comparing((Address a) -> a.name).thenComparing(Address::host));
    return Collections.unmodifiableList(found);
  }

  public static Address best(List<Address> available, InetAddress peer, String serverIp) {
    return available.stream()
        .max(
            Comparator.comparingInt((Address a) -> score(a, peer, serverIp))
                .thenComparing(Address::host, Comparator.reverseOrder()))
        .orElse(null);
  }

  private static int score(Address a, InetAddress peer, String serverIp) {
    int score = a.ip instanceof Inet4Address ? 100 : 30;
    if (a.ip.isSiteLocalAddress()) score += 60;
    if (a.virtual) score -= 250;
    if (a.ip.isLinkLocalAddress()) score -= 100;
    if (a.host().equals(serverIp)) score += 300;
    if (a.ip.isLoopbackAddress())
      return peer != null && peer.isLoopbackAddress() ? score + 10000 : score - 10000;
    if (peer != null && !peer.isLoopbackAddress() && a.contains(peer))
      score += 2000 + Math.max(a.prefix, 0);
    return score;
  }

  private static boolean virtualName(NetworkInterface n) {
    String name = n.getName().toLowerCase(Locale.ROOT);
    String label = String.valueOf(n.getDisplayName()).toLowerCase(Locale.ROOT);
    return name.matches("(docker|veth|virbr|vmnet|vboxnet|br-|tun|tap).*")
        || label.contains("virtual")
        || label.contains("vmware")
        || label.contains("hyper-v");
  }

  private static String clean(String input) {
    String value = input == null ? "network" : input.replaceAll("[\\p{Cntrl}]", " ");
    return value.substring(0, Math.min(value.length(), 96));
  }

  public static InetAddress loopback() {
    try {
      return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
