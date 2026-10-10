package me.snowsun.tabprefix.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ConfigurationSnapshot {
  public final PluginSettings settings;
  public final Map<String, String> messages;

  public ConfigurationSnapshot(PluginSettings settings, Map<String, String> messages) {
    this.settings = settings;
    this.messages = Collections.unmodifiableMap(new HashMap<>(messages));
  }
}
