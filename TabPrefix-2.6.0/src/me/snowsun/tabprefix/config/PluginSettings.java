package me.snowsun.tabprefix.config;

import java.io.IOException;
import java.nio.file.Path;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import me.snowsun.tabprefix.infrastructure.platform.PlatformInfo;
import me.snowsun.tabprefix.util.SafePaths;
import org.bukkit.configuration.file.YamlConfiguration;

/** Validated immutable settings for the plugin. */
public final class PluginSettings {
  public final boolean luckPermsEnabled;
  public final boolean debug, prefixEnabled, usePrimaryGroup, tabEnabled, chatEnabled;
  public final boolean listenForUpdates, miniMessageEnabled, colors, decorations, clicks, hovers;
  public final boolean rejectUnsupported, spigot, paper, purpur;
  public final String separator;
  public final FeatureSettings features;
  public final MinecraftVersion minVersion, maxVersion, minecraft;
  public final Path databaseFile, dataDirectory, tempDirectory, packsDirectory, stagingDirectory;

  public PluginSettings(YamlConfiguration yaml, Path root) throws IOException {
    this(yaml, root, MinecraftVersion.parse("1.16.5"));
  }

  public PluginSettings(YamlConfiguration yaml, Path root, MinecraftVersion minecraft)
      throws IOException {
    this.minecraft = java.util.Objects.requireNonNull(minecraft);
    features = new FeatureSettings(new YamlSettings(yaml));
    debug = bool(yaml, "general.debug");
    prefixEnabled = bool(yaml, "prefix.enabled");
    usePrimaryGroup = bool(yaml, "prefix.use-primary-group");
    tabEnabled = bool(yaml, "display.tab-list.enabled");
    chatEnabled = bool(yaml, "display.chat.enabled");
    listenForUpdates = bool(yaml, "luckperms.listen-for-updates");
    luckPermsEnabled = bool(yaml, "luckperms.enabled");
    miniMessageEnabled = bool(yaml, "minimessage.enabled");
    colors = bool(yaml, "minimessage.allow-colors");
    decorations = bool(yaml, "minimessage.allow-decorations");
    clicks = bool(yaml, "minimessage.allow-click-events");
    hovers = bool(yaml, "minimessage.allow-hover-events");
    rejectUnsupported = bool(yaml, "compatibility.reject-unsupported-version");
    spigot = bool(yaml, "compatibility.platforms.spigot");
    paper = bool(yaml, "compatibility.platforms.paper");
    purpur = bool(yaml, "compatibility.platforms.purpur");
    Object configuredSeparator = yaml.get("prefix.separator", " ");
    if (!(configuredSeparator instanceof String))
      throw new IllegalArgumentException("prefix.separator must be a string.");
    separator = (String) configuredSeparator;
    if (separator.length() > 16 || separator.contains("\n") || separator.contains("\r")) {
      throw new IllegalArgumentException(
          "prefix.separator must be a single line of at most 16 characters.");
    }
    String min = string(yaml, "compatibility.minecraft.min-version");
    String max = string(yaml, "compatibility.minecraft.max-version");
    // Only the exact factory range from 2.0-2.3 is migrated. Custom limits remain authoritative.
    boolean oldDefaults = min.equals("1.16") && max.equals("1.16.5");
    minVersion = MinecraftVersion.parse(oldDefaults ? "1.12" : min);
    maxVersion = MinecraftVersion.parse(oldDefaults ? "26.3.99" : max);
    if (minVersion.compareTo(maxVersion) > 0)
      throw new IllegalArgumentException("Invalid Minecraft version range.");
    if (!"sqlite".equalsIgnoreCase(string(yaml, "storage.type"))) {
      throw new IllegalArgumentException("Only storage.type: sqlite is supported.");
    }
    databaseFile = SafePaths.inside(root, string(yaml, "storage.sqlite.file"));
    dataDirectory = SafePaths.inside(root, string(yaml, "storage.data-directory"));
    tempDirectory = SafePaths.inside(root, string(yaml, "storage.temp-directory"));
    packsDirectory = SafePaths.inside(root, string(yaml, "storage.packs-directory"));
    stagingDirectory = SafePaths.inside(root, string(yaml, "storage.staging-directory"));
    Path[] folders = {dataDirectory, tempDirectory, packsDirectory, stagingDirectory};
    for (int i = 0; i < folders.length; i++) {
      if (databaseFile.startsWith(folders[i]))
        throw new IllegalArgumentException("SQLite must be outside managed asset directories.");
      for (int j = 0; j < i; j++)
        if (folders[i].startsWith(folders[j]) || folders[j].startsWith(folders[i]))
          throw new IllegalArgumentException(
              "Storage directories must be distinct and must not overlap.");
    }
  }

  public void validatePlatform(PlatformInfo info) {
    boolean allowed =
        info.kind == PlatformInfo.Kind.PURPUR
            ? purpur
            : info.kind == PlatformInfo.Kind.PAPER ? paper : spigot;
    if (!allowed) throw new IllegalArgumentException("Platform disabled in config: " + info.name);
    if (rejectUnsupported
        && (!info.minecraft.supported()
            || info.minecraft.compareTo(minVersion) < 0
            || info.minecraft.compareTo(maxVersion) > 0)) {
      throw new IllegalArgumentException(
          "Unsupported Minecraft "
              + info.minecraft
              + "; supported: "
              + MinecraftVersion.SUPPORTED
              + "; configured range: "
              + minVersion
              + "-"
              + maxVersion
              + ".");
    }
  }

  private static boolean bool(YamlConfiguration yaml, String key) {
    Object value = yaml.get(key);
    if (!(value instanceof Boolean))
      throw new IllegalArgumentException(key + " must be true or false.");
    return (Boolean) value;
  }

  private static String string(YamlConfiguration yaml, String key) {
    Object value = yaml.get(key);
    if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
      throw new IllegalArgumentException(key + " must be a non-empty string.");
    }
    return ((String) value).trim();
  }
}
