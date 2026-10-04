package me.snowsun.tabprefix.domain;

public final class RuntimeStatus {
  public final String version, minecraft, platform;
  public final boolean ready;
  public final int textPrefixes;

  public RuntimeStatus(
      String version, String minecraft, String platform, boolean ready, int textPrefixes) {
    this.version = version;
    this.minecraft = minecraft;
    this.platform = platform;
    this.ready = ready;
    this.textPrefixes = textPrefixes;
  }
}
