package me.snowsun.tabprefix.infrastructure.web;

import java.net.*;
import me.snowsun.tabprefix.config.FeatureSettings;

public final class PublicAddress {
  private PublicAddress() {}

  public static String resolve(FeatureSettings f, String serverIp) {
    if (!f.editorBase.isEmpty()) return f.editorBase;
    String host = f.publicHost;
    if (host.isEmpty()) {
      host = serverIp == null ? "" : serverIp;
      if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("::")) {
        try {
          host = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
          host = "127.0.0.1";
        }
      }
    }
    if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
    return "http://" + host + ":" + f.port;
  }
}
