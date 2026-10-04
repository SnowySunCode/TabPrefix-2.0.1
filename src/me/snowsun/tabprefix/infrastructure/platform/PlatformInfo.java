package me.snowsun.tabprefix.infrastructure.platform;

import me.snowsun.tabprefix.domain.MinecraftVersion;
import org.bukkit.Server;

public final class PlatformInfo {
  public enum Kind {
    BUKKIT,
    SPIGOT,
    PAPER,
    PURPUR
  }

  public final Kind kind;
  public final String name;
  public final MinecraftVersion minecraft;

  private PlatformInfo(Kind kind, String name, MinecraftVersion minecraft) {
    this.kind = kind;
    this.name = name;
    this.minecraft = minecraft;
  }

  public static PlatformInfo detect(Server server) {
    String name = server.getName();
    String normalized = name.toLowerCase(java.util.Locale.ROOT);
    Kind kind =
        normalized.contains("purpur")
            ? Kind.PURPUR
            : normalized.contains("paper")
                ? Kind.PAPER
                : normalized.contains("spigot") ? Kind.SPIGOT : Kind.BUKKIT;
    ClassLoader loader = server.getClass().getClassLoader();
    if (present("org.purpurmc.purpur.PurpurConfig", loader)
        || present("net.pl3x.purpur.PurpurConfig", loader)) kind = Kind.PURPUR;
    else if (kind != Kind.PURPUR && present("com.destroystokyo.paper.PaperConfig", loader))
      kind = Kind.PAPER;
    return new PlatformInfo(kind, name, MinecraftVersion.parse(server.getBukkitVersion()));
  }

  private static boolean present(String name, ClassLoader loader) {
    try {
      Class.forName(name, false, loader);
      return true;
    } catch (ClassNotFoundException | LinkageError ignored) {
      return false;
    }
  }
}
