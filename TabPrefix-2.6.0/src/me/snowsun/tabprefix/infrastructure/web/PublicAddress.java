package me.snowsun.tabprefix.infrastructure.web;

import me.snowsun.tabprefix.config.FeatureSettings;

public final class PublicAddress {
  private PublicAddress() {}

  public static String resolve(FeatureSettings f, String serverIp) {
    return new WebAddresses(f, serverIp).base(null, null);
  }
}
