package me.snowsun.tabprefix.config;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** YAML adapter; immutable feature settings themselves have no Bukkit dependency. */
public final class YamlSettings implements FeatureSettings.Source {
  private final YamlConfiguration yaml;

  public YamlSettings(YamlConfiguration yaml) {
    this.yaml = yaml;
  }

  @Override
  public Object get(String key, Object fallback) {
    Object value = yaml.get(key);
    return value == null ? fallback : value;
  }

  @Override
  public Set<String> keys(String path) {
    ConfigurationSection section = yaml.getConfigurationSection(path);
    return section == null ? Collections.emptySet() : section.getKeys(false);
  }
}
