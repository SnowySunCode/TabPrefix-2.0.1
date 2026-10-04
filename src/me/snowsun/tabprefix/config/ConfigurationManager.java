package me.snowsun.tabprefix.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Parses candidates completely before an active configuration is replaced. */
public final class ConfigurationManager {
  private final JavaPlugin plugin;
  private final Path root;

  public ConfigurationManager(JavaPlugin plugin) throws IOException {
    this.plugin = plugin;
    Files.createDirectories(plugin.getDataFolder().toPath());
    root = plugin.getDataFolder().toPath().toRealPath();
  }

  public ConfigurationSnapshot load() throws IOException, InvalidConfigurationException {
    plugin.saveDefaultConfig();
    YamlConfiguration config = read("config.yml");
    PluginSettings settings = new PluginSettings(config, root);
    String file = settings.features.language.equals("ru") ? "messages_ru.yml" : "messages.yml";
    if (!Files.exists(root.resolve(file))) plugin.saveResource(file, false);
    YamlConfiguration messages = read(file);
    Map<String, String> catalog = new HashMap<>();
    for (Map.Entry<String, Object> entry : messages.getValues(true).entrySet()) {
      if (entry.getValue() instanceof ConfigurationSection) continue;
      if (!(entry.getValue() instanceof String)) {
        throw new IllegalArgumentException("Message " + entry.getKey() + " must be a string.");
      }
      catalog.put(entry.getKey(), (String) entry.getValue());
    }
    return new ConfigurationSnapshot(settings, catalog);
  }

  private YamlConfiguration read(String name) throws IOException, InvalidConfigurationException {
    YamlConfiguration defaults = new YamlConfiguration();
    InputStream stream = plugin.getResource(name);
    if (stream == null) throw new IOException("Missing bundled resource: " + name);
    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      defaults.load(reader);
    }
    YamlConfiguration candidate = new YamlConfiguration();
    try (Reader reader = Files.newBufferedReader(root.resolve(name), StandardCharsets.UTF_8)) {
      candidate.load(reader);
    }
    candidate.setDefaults(defaults);
    candidate.options().copyDefaults(true);
    return candidate;
  }
}
