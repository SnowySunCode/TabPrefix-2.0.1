package me.snowsun.tabprefix.infrastructure.bukkit;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import me.snowsun.tabprefix.domain.PackRevision;
import org.bukkit.entity.Player;

/**
 * Pack IDs isolate modern requests from other plugins' packs. Legacy requests remain serialized.
 */
public final class ResourcePackAccess {
  private final Method add, remove;

  public ResourcePackAccess() {
    Method a = null, r = null;
    try {
      a =
          Player.class.getMethod(
              "addResourcePack",
              UUID.class,
              String.class,
              byte[].class,
              String.class,
              boolean.class);
      r = Player.class.getMethod("removeResourcePack", UUID.class);
    } catch (NoSuchMethodException ignored) {
    }
    add = a;
    remove = r;
  }

  public static UUID id(PackRevision revision) {
    return UUID.nameUUIDFromBytes(
        ("TabPrefix/resource/" + revision.hash).getBytes(StandardCharsets.UTF_8));
  }

  public void send(Player player, PackRevision revision, String url, byte[] hash) {
    if (add == null) {
      player.setResourcePack(url, hash);
      return;
    }
    try {
      add.invoke(player, id(revision), url, hash, null, false);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot offer resource pack", e);
    }
  }

  public boolean matches(Object event, PackRevision requested) {
    if (requested == null) return false;
    try {
      return id(requested).equals(event.getClass().getMethod("getID").invoke(event));
    } catch (NoSuchMethodException ignored) {
      return true;
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  public void remove(Player player, PackRevision revision) {
    if (remove == null || revision == null) return;
    try {
      remove.invoke(player, id(revision));
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot remove resource pack", e);
    }
  }
}
