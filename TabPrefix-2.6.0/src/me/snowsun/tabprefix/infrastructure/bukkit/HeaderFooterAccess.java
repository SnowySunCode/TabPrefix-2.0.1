package me.snowsun.tabprefix.infrastructure.bukkit;

import java.lang.reflect.*;
import java.util.*;
import java.util.logging.*;
import me.snowsun.tabprefix.domain.MinecraftVersion;
import org.bukkit.entity.Player;

/** Uses Bukkit when available and the native header/footer packet on older servers. */
public final class HeaderFooterAccess {
  private final Logger logger;
  private final NativeTabAccess packets = new NativeTabAccess();
  private final Map<UUID, String[]> remembered = new HashMap<>();
  private Method getHeader, getFooter, setHeader, setFooter;
  private boolean failed;

  public HeaderFooterAccess(Logger logger, MinecraftVersion version) {
    this.logger = logger;
    // On 1.12 Paper-specific send methods have different signatures; use the vanilla packet.
    if (version.compareTo(MinecraftVersion.parse("1.13")) >= 0)
      try {
        getHeader = Player.class.getMethod("getPlayerListHeader");
        getFooter = Player.class.getMethod("getPlayerListFooter");
        setHeader = Player.class.getMethod("setPlayerListHeader", String.class);
        setFooter = Player.class.getMethod("setPlayerListFooter", String.class);
      } catch (NoSuchMethodException e) {
        getHeader = null;
      }
  }

  public String header(Player player) {
    return get(player, true);
  }

  public String footer(Player player) {
    return get(player, false);
  }

  private String get(Player player, boolean header) {
    if (getHeader != null)
      try {
        return (String) (header ? getHeader : getFooter).invoke(player);
      } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
        fail(e);
      }
    String[] pair = remembered.get(player.getUniqueId());
    return pair == null ? null : pair[header ? 0 : 1];
  }

  public void header(Player player, String text) {
    set(player, true, text);
  }

  public void footer(Player player, String text) {
    set(player, false, text);
  }

  private void set(Player player, boolean header, String text) {
    if (failed) return;
    try {
      if (getHeader != null) (header ? setHeader : setFooter).invoke(player, text);
      else {
        String[] old = remembered.getOrDefault(player.getUniqueId(), new String[2]);
        packets.headerFooter(player, header ? text : old[0], header ? old[1] : text);
        String[] next = old.clone();
        next[header ? 0 : 1] = text;
        remembered.put(player.getUniqueId(), next);
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
      fail(e);
    }
  }

  private void fail(Throwable e) {
    if (!failed) logger.log(Level.WARNING, "Header/footer adapter unavailable.", e);
    failed = true;
  }

  public void forget(UUID id) {
    remembered.remove(id);
  }

  public void clear() {
    remembered.clear();
  }

  public String diagnostic() {
    return "headerFooter="
        + (failed ? "unavailable" : getHeader == null ? "native-legacy" : "bukkit");
  }
}
