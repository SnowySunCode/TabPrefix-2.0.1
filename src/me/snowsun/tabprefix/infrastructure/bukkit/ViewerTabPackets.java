package me.snowsun.tabprefix.infrastructure.bukkit;

import java.lang.reflect.*;
import java.util.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Reflection adapter for CraftBukkit v1_16_R1/R2/R3; one batched display-name packet per viewer.
 */
public final class ViewerTabPackets {
  private final JavaPlugin plugin;
  private final LinkedHashMap<String, String> jsonCache = new LinkedHashMap<>(128, .75f, true);
  private int cacheCharacters;
  private final LegacyComponentSerializer legacy =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();
  private Constructor<?> constructor;
  private Method handle, parse, send;
  private Field connection, list, display;
  private Object action;
  private Class<?> entity;
  private boolean available = true, initialized;

  public ViewerTabPackets(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void initialize(Player sample) throws ReflectiveOperationException {
    if (initialized) return;
    handle = sample.getClass().getMethod("getHandle");
    String craft = handle.getReturnType().getPackage().getName();
    String revision = craft.substring(craft.lastIndexOf('.') + 1);
    if (!revision.matches("v1_16_R[123]"))
      throw new ClassNotFoundException("Unsupported CraftBukkit revision: " + revision);
    String nms = "net.minecraft.server." + revision + ".";
    entity = Class.forName(nms + "EntityPlayer");
    Class<?> packet = Class.forName(nms + "PacketPlayOutPlayerInfo"),
        enumClass = Class.forName(nms + "PacketPlayOutPlayerInfo$EnumPlayerInfoAction"),
        component = Class.forName(nms + "IChatBaseComponent");
    action = Enum.valueOf((Class<? extends Enum>) enumClass, "UPDATE_DISPLAY_NAME");
    constructor = packet.getConstructor(enumClass, Array.newInstance(entity, 0).getClass());
    for (Field field : packet.getDeclaredFields())
      if (List.class.isAssignableFrom(field.getType())) {
        list = field;
        list.setAccessible(true);
        break;
      }
    Class<?> data = Class.forName(nms + "PacketPlayOutPlayerInfo$PlayerInfoData");
    for (Field field : data.getDeclaredFields())
      if (component.isAssignableFrom(field.getType())) {
        display = field;
        display.setAccessible(true);
        break;
      }
    connection = entity.getField("playerConnection");
    send = connection.getType().getMethod("sendPacket", Class.forName(nms + "Packet"));
    parse = Class.forName(nms + "IChatBaseComponent$ChatSerializer").getMethod("a", String.class);
    if (list == null || display == null)
      throw new NoSuchFieldException("PlayerInfo display-name fields.");
    initialized = true;
  }

  private String json(String name) {
    String value = jsonCache.get(name);
    if (value != null) return value;
    value = GsonComponentSerializer.gson().serialize(legacy.deserialize(name));
    jsonCache.put(name, value);
    cacheCharacters += name.length() + value.length();
    while (jsonCache.size() > 4096 || cacheCharacters > 2000000) {
      Map.Entry<String, String> first = jsonCache.entrySet().iterator().next();
      cacheCharacters -= first.getKey().length() + first.getValue().length();
      jsonCache.remove(first.getKey());
    }
    return value;
  }

  public boolean send(Player viewer, Map<Player, String> names) {
    if (!available || names.isEmpty()) return available;
    try {
      initialize(viewer);
      Object players = Array.newInstance(entity, names.size());
      int index = 0;
      for (Player player : names.keySet()) Array.set(players, index++, handle.invoke(player));
      Object packet = constructor.newInstance(action, players);
      List<?> entries = (List<?>) list.get(packet);
      if (entries.size() != names.size())
        throw new ReflectiveOperationException("Unexpected PlayerInfo list size.");
      index = 0;
      for (String name : names.values()) {
        String json = json(name);
        display.set(entries.get(index++), parse.invoke(null, json));
      }
      send.invoke(connection.get(handle.invoke(viewer)), packet);
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
      available = false;
      plugin
          .getLogger()
          .log(
              java.util.logging.Level.WARNING,
              "Per-viewer TAB graphics unavailable; text fallback remains active.",
              error);
      return false;
    }
  }
}
