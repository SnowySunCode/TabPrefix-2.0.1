package me.snowsun.tabprefix.infrastructure.bukkit;

import java.lang.reflect.*;
import java.util.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Runtime-only access to legacy, Spigot and Mojang packet names. No NMS, CraftBukkit or authlib
 * classes appear in plugin bytecode signatures. All calls are confined to the server thread.
 */
final class NativeTabAccess {
  private final LegacyComponentSerializer legacy =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();
  private final Map<String, Object> components =
      new LinkedHashMap<String, Object>(128, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Object> e) {
          return size() > 512;
        }
      };
  private ClassLoader loader;
  private Class<?> entity, packetType, componentType, profileType, actionType;
  private String oldPackage, craftPackage;
  private Method handle, send, parse, profile, properties, apiPing;
  private Field connection, list, ping, display;
  private Constructor<?> packetConstructor, entryConstructor, removeConstructor;
  private List<Field> entryFields;
  private Class<?>[] entryTypes;
  private boolean ready, modern;

  void initialize(Player sample) throws ReflectiveOperationException {
    if (ready) return;
    loader = sample.getClass().getClassLoader();
    handle = sample.getClass().getMethod("getHandle");
    entity = handle.getReturnType();
    String pkg = entity.getPackage().getName();
    oldPackage = pkg.matches("net\\.minecraft\\.server\\.v1_\\d+_R\\d+") ? pkg + "." : null;
    craftPackage = sample.getClass().getPackage().getName();
    if (craftPackage.endsWith(".entity"))
      craftPackage = craftPackage.substring(0, craftPackage.length() - 7);
    packetType =
        load(oldPackage == null ? "net.minecraft.network.protocol.Packet" : oldPackage + "Packet");
    componentType =
        load(
            oldPackage == null
                ? "net.minecraft.network.chat.Component"
                : oldPackage + "IChatBaseComponent",
            "net.minecraft.network.chat.IChatBaseComponent");
    profileType = load("com.mojang.authlib.GameProfile");
    profile = returning(entity, profileType, "getGameProfile", "getProfile");
    properties = named(profileType, "getProperties", "properties");
    findTransport();
    findParser();
    try {
      apiPing = Player.class.getMethod("getPing");
    } catch (NoSuchMethodException ignored) {
    }
    ping = fieldNamed(entity, "ping", "latency");
    Class<?> packet;
    if (oldPackage != null) packet = load(oldPackage + "PacketPlayOutPlayerInfo");
    else {
      packet =
          optional(
              "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket",
              "net.minecraft.network.protocol.game.PacketPlayOutPlayerInfoUpdate");
      modern = packet != null;
      if (!modern)
        packet =
            load(
                "net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket",
                "net.minecraft.network.protocol.game.PacketPlayOutPlayerInfo");
    }
    list = fieldOf(packet, List.class);
    for (Constructor<?> c : packet.getDeclaredConstructors()) {
      Class<?>[] t = c.getParameterTypes();
      if (t.length != 2) continue;
      if (modern
          && EnumSet.class.isAssignableFrom(t[0])
          && Collection.class.isAssignableFrom(t[1])) {
        // Prefer the vanilla factory taking ServerPlayers. Entries are installed after
        // construction.
        if (packetConstructor == null || t[1] == Collection.class)
          packetConstructor = accessible(c);
      } else if (!modern
          && t[0].isEnum()
          && t[1].isArray()
          && t[1].getComponentType().isAssignableFrom(entity)) {
        packetConstructor = accessible(c);
        actionType = t[0];
      }
    }
    if (packetConstructor == null)
      throw new NoSuchMethodException("Player-info packet constructor: " + packet.getName());
    Class<?> entry = null;
    for (Class<?> nested : packet.getDeclaredClasses()) {
      if (nested.isEnum() && hasEnum(nested, "UPDATE_DISPLAY_NAME")) actionType = nested;
      for (Field f : nested.getDeclaredFields())
        if (!Modifier.isStatic(f.getModifiers()) && f.getType() == profileType) entry = nested;
    }
    if (actionType == null || entry == null)
      throw new NoSuchFieldException("Player-info action/entry types");
    display = fieldOf(entry, componentType);
    for (Constructor<?> c : entry.getDeclaredConstructors()) {
      Class<?>[] t = c.getParameterTypes();
      boolean hasProfile = false, hasComponent = false;
      for (Class<?> type : t) {
        hasProfile |= type == profileType;
        hasComponent |= type == componentType;
      }
      if (hasProfile
          && hasComponent
          && (entryConstructor == null || t.length > entryTypes.length)) {
        entryConstructor = accessible(c);
        entryTypes = t;
      }
    }
    if (entryConstructor == null) throw new NoSuchMethodException("Player-info entry constructor");
    if (modern) {
      entryFields = canonicalFields(entry, entryTypes);
      Class<?> remove =
          load(
              "net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket",
              "net.minecraft.network.protocol.game.PacketPlayOutPlayerInfoRemove");
      for (Constructor<?> c : remove.getDeclaredConstructors())
        if (c.getParameterTypes().length == 1
            && Collection.class.isAssignableFrom(c.getParameterTypes()[0]))
          removeConstructor = accessible(c);
      if (removeConstructor == null)
        throw new NoSuchMethodException("Player-info removal constructor");
    }
    ready = true;
  }

  private void findTransport() throws ReflectiveOperationException {
    List<Field> fields = fields(entity);
    fields.sort(
        Comparator.comparingInt(
            f ->
                f.getName().equals("connection") || f.getName().equals("playerConnection")
                    ? 0
                    : 1));
    for (Field f : fields) {
      if (Modifier.isStatic(f.getModifiers())) continue;
      for (Method m : f.getType().getMethods()) {
        Class<?>[] t = m.getParameterTypes();
        if (t.length == 1
            && t[0] == packetType
            && m.getReturnType() == void.class
            && (m.getName().equals("send")
                || m.getName().equals("sendPacket")
                || m.getName().equals("a"))) {
          connection = accessible(f);
          send = m;
          return;
        }
      }
    }
    throw new NoSuchFieldException("Server-player packet connection");
  }

  private void findParser() throws ReflectiveOperationException {
    Class<?> craft =
        optional(
            craftPackage + ".util.CraftChatMessage",
            "org.bukkit.craftbukkit.util.CraftChatMessage",
            oldPackage == null
                ? ""
                : "org.bukkit.craftbukkit."
                    + oldPackage.substring(21, oldPackage.length() - 1)
                    + ".util.CraftChatMessage");
    if (craft != null) {
      try {
        parse = craft.getMethod("fromJSON", String.class);
        return;
      } catch (NoSuchMethodException ignored) {
      }
    }
    // Legacy 1.12-1.20.4 serializers require no registry argument. Modern servers use
    // CraftChatMessage.
    for (Class<?> nested : componentType.getDeclaredClasses())
      for (Method m : nested.getDeclaredMethods())
        if (Modifier.isStatic(m.getModifiers())
            && componentType.isAssignableFrom(m.getReturnType())
            && Arrays.equals(m.getParameterTypes(), new Class<?>[] {String.class})) {
          parse = accessible(m);
          return;
        }
    throw new NoSuchMethodException("Chat component JSON parser");
  }

  Object component(String text) throws ReflectiveOperationException {
    Object cached = components.get(text);
    if (cached == null) {
      cached =
          parse.invoke(null, GsonComponentSerializer.gson().serialize(legacy.deserialize(text)));
      if (cached == null) throw new ReflectiveOperationException("Null chat component");
      components.put(text, cached);
    }
    return cached;
  }

  Object packet(String action, Collection<Player> players) throws ReflectiveOperationException {
    Object values;
    if (modern) {
      List<Object> handles = new ArrayList<>();
      for (Player p : players) handles.add(handle.invoke(p));
      values = handles;
    } else {
      values = Array.newInstance(entity, players.size());
      int i = 0;
      for (Player p : players) Array.set(values, i++, handle.invoke(p));
    }
    return packetConstructor.newInstance(actions(action), values);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Object actions(String name) {
    Object value = Enum.valueOf((Class) actionType, name);
    if (!modern) return value;
    EnumSet set = EnumSet.of((Enum) value);
    if (name.equals("ADD_PLAYER"))
      for (String other :
          new String[] {
            "UPDATE_GAME_MODE",
            "UPDATE_LISTED",
            "UPDATE_LATENCY",
            "UPDATE_DISPLAY_NAME",
            "UPDATE_LIST_ORDER",
            "UPDATE_HAT"
          }) if (hasEnum(actionType, other)) set.add(Enum.valueOf((Class) actionType, other));
    return set;
  }

  @SuppressWarnings("unchecked")
  List<Object> entries(Object packet) throws IllegalAccessException {
    return (List<Object>) list.get(packet);
  }

  void entries(Object packet, List<Object> entries) throws IllegalAccessException {
    list.set(packet, entries);
  }

  void rename(Object packet, Map<Player, String> names) throws ReflectiveOperationException {
    List<Object> changed = new ArrayList<>();
    List<Object> originals = entries(packet);
    if (originals.size() != names.size())
      throw new ReflectiveOperationException("Unexpected player-info list size");
    int i = 0;
    for (String name : names.values()) {
      Object original = originals.get(i++), text = component(name);
      if (!modern) {
        display.set(original, text);
        changed.add(original);
      } else {
        Object[] args = new Object[entryFields.size()];
        for (int n = 0; n < args.length; n++) args[n] = entryFields.get(n).get(original);
        for (int n = 0; n < entryTypes.length; n++)
          if (entryTypes[n] == componentType) args[n] = text;
        changed.add(entryConstructor.newInstance(args));
      }
    }
    entries(packet, changed);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  Object entry(Object packet, UUID id, int slot, VirtualTabPackets.Cell cell)
      throws ReflectiveOperationException {
    Object real = cell == null || cell.player == null ? null : handle.invoke(cell.player);
    Object skin = real == null ? null : profile.invoke(real);
    Object synthetic = syntheticProfile(id, String.format(Locale.ROOT, "!tp%03d", slot), skin);
    Object[] args = new Object[entryTypes.length];
    int integers = 0;
    for (int n = 0; n < args.length; n++) {
      Class<?> t = entryTypes[n];
      if (t == profileType) args[n] = synthetic;
      else if (t == componentType) args[n] = component(cell == null ? "" : cell.text);
      else if (t == UUID.class) args[n] = id;
      else if (t == int.class) {
        args[n] =
            integers++ == 0
                ? (cell != null && cell.player != null ? ping(cell.player) : 0)
                : Integer.MAX_VALUE - slot;
      } else if (t == boolean.class) args[n] = true; // listed and showHat
      else if (t.isEnum() && hasEnum(t, "SURVIVAL")) args[n] = Enum.valueOf((Class) t, "SURVIVAL");
      else if (t.isInstance(packet)) args[n] = packet; // non-static legacy inner entry
      else if (t.isPrimitive())
        throw new NoSuchMethodException("Unknown player-info primitive: " + t);
      // Nullable public key / chat session must stay absent for synthetic profiles.
    }
    return entryConstructor.newInstance(args);
  }

  private Object syntheticProfile(UUID id, String name, Object skin)
      throws ReflectiveOperationException {
    Object props = skin == null ? null : properties.invoke(skin);
    // New authlib profiles contain an immutable PropertyMap in their canonical constructor.
    for (Constructor<?> c : profileType.getConstructors()) {
      Class<?>[] t = c.getParameterTypes();
      if (t.length == 3
          && t[0] == UUID.class
          && t[1] == String.class
          && t[2] == properties.getReturnType())
        return c.newInstance(id, name, props == null ? emptyProperties(t[2]) : props);
    }
    Object target = profileType.getConstructor(UUID.class, String.class).newInstance(id, name);
    if (props != null) {
      Object destination = properties.invoke(target);
      Method copier = null;
      for (Method m : destination.getClass().getMethods())
        if (m.getName().equals("putAll")
            && m.getParameterTypes().length == 1
            && m.getParameterTypes()[0].isInstance(props)) {
          copier = m;
          break;
        }
      if (copier == null) throw new NoSuchMethodException("Profile properties copy");
      copier.invoke(destination, props);
    }
    return target;
  }

  private Object emptyProperties(Class<?> type) throws ReflectiveOperationException {
    for (Field f : type.getFields())
      if (Modifier.isStatic(f.getModifiers())
          && f.getName().equals("EMPTY")
          && type.isAssignableFrom(f.getType())) return f.get(null);
    try {
      return type.getConstructor().newInstance();
    } catch (NoSuchMethodException ignored) {
    }
    for (Constructor<?> c : type.getConstructors()) {
      Class<?>[] t = c.getParameterTypes();
      if (t.length != 1) continue;
      if (Map.class.isAssignableFrom(t[0])) return c.newInstance(Collections.emptyMap());
      try {
        Class<?> immutable = load("com.google.common.collect.ImmutableMultimap");
        Object empty = immutable.getMethod("of").invoke(null);
        if (t[0].isInstance(empty)) return c.newInstance(empty);
      } catch (ClassNotFoundException ignored) {
      }
    }
    throw new NoSuchMethodException("Empty profile properties");
  }

  void remove(Player viewer, UUID[] ids) throws ReflectiveOperationException {
    Object packet;
    if (modern) packet = removeConstructor.newInstance(Arrays.asList(ids));
    else {
      packet = packet("REMOVE_PLAYER", Collections.emptyList());
      List<Object> entries = new ArrayList<>();
      for (int i = 0; i < ids.length; i++) entries.add(entry(packet, ids[i], i, null));
      entries(packet, entries);
    }
    send(viewer, packet);
  }

  void send(Player viewer, Object packet) throws ReflectiveOperationException {
    send.invoke(connection.get(handle.invoke(viewer)), packet);
  }

  int ping(Player player) {
    try {
      if (apiPing == null)
        try {
          apiPing = Player.class.getMethod("getPing");
        } catch (NoSuchMethodException ignored) {
        }
      if (apiPing != null) return Math.max(0, ((Number) apiPing.invoke(player)).intValue());
      if (!ready) initialize(player);
      if (ping != null && ping.getType() == int.class)
        return Math.max(0, ping.getInt(handle.invoke(player)));
      Object listener = connection.get(handle.invoke(player));
      for (String name : new String[] {"latency", "getLatency"})
        try {
          return Math.max(
              0, ((Number) listener.getClass().getMethod(name).invoke(listener)).intValue());
        } catch (NoSuchMethodException ignored) {
        }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
    }
    return 0;
  }

  void headerFooter(Player viewer, String top, String bottom) throws ReflectiveOperationException {
    initialize(viewer);
    Class<?> packet =
        load(
            oldPackage == null
                ? "net.minecraft.network.protocol.game.ClientboundTabListPacket"
                : oldPackage + "PacketPlayOutPlayerListHeaderFooter",
            "net.minecraft.network.protocol.game.PacketPlayOutPlayerListHeaderFooter");
    Object header = component(top == null ? "" : top),
        footer = component(bottom == null ? "" : bottom),
        value = null;
    for (Constructor<?> c : packet.getDeclaredConstructors())
      if (Arrays.equals(c.getParameterTypes(), new Class<?>[] {componentType, componentType})) {
        value = accessible(c).newInstance(header, footer);
        break;
      }
    if (value == null) {
      try {
        value = accessible(packet.getDeclaredConstructor()).newInstance();
      } catch (NoSuchMethodException e) {
        value = accessible(packet.getDeclaredConstructor(componentType)).newInstance(header);
      }
      List<Field> texts = new ArrayList<>();
      for (Field f : fields(packet))
        if (!Modifier.isStatic(f.getModifiers()) && f.getType() == componentType)
          texts.add(accessible(f));
      if (texts.size() != 2) throw new NoSuchFieldException("Header/footer components");
      texts.get(0).set(value, header);
      texts.get(1).set(value, footer);
    }
    send(viewer, value);
  }

  String family() {
    return !ready
        ? "pending"
        : modern ? "player-info-update" : oldPackage == null ? "player-info" : "legacy-player-info";
  }

  private Class<?> load(String... names) throws ClassNotFoundException {
    Class<?> type = optional(names);
    if (type == null) throw new ClassNotFoundException(Arrays.toString(names));
    return type;
  }

  private Class<?> optional(String... names) {
    for (String name : names) {
      if (name.isEmpty()) continue;
      try {
        return Class.forName(name, false, loader);
      } catch (ClassNotFoundException ignored) {
      }
    }
    return null;
  }

  private static List<Field> fields(Class<?> type) {
    List<Field> result = new ArrayList<>();
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
      result.addAll(Arrays.asList(c.getDeclaredFields()));
    return result;
  }

  private static Field fieldNamed(Class<?> type, String... names) {
    for (String name : names)
      for (Field f : fields(type)) if (f.getName().equals(name)) return accessible(f);
    return null;
  }

  private static Field fieldOf(Class<?> owner, Class<?> type) throws NoSuchFieldException {
    for (Field f : fields(owner))
      if (!Modifier.isStatic(f.getModifiers()) && type.isAssignableFrom(f.getType()))
        return accessible(f);
    throw new NoSuchFieldException(owner.getName() + ": " + type.getName());
  }

  private static Method returning(Class<?> owner, Class<?> type, String... names)
      throws NoSuchMethodException {
    for (String name : names)
      try {
        Method m = owner.getMethod(name);
        if (m.getReturnType() == type) return m;
      } catch (NoSuchMethodException ignored) {
      }
    for (Method m : owner.getMethods())
      if (m.getParameterTypes().length == 0 && m.getReturnType() == type) return m;
    throw new NoSuchMethodException(owner.getName() + ": " + type.getName());
  }

  private static Method named(Class<?> owner, String... names) throws NoSuchMethodException {
    for (String name : names)
      try {
        return owner.getMethod(name);
      } catch (NoSuchMethodException ignored) {
      }
    throw new NoSuchMethodException(owner.getName() + ": " + Arrays.toString(names));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static boolean hasEnum(Class<?> type, String name) {
    if (!type.isEnum()) return false;
    try {
      Enum.valueOf((Class) type, name);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Record components provide canonical order even when Spigot renames their fields. */
  private static List<Field> canonicalFields(Class<?> entry, Class<?>[] types)
      throws ReflectiveOperationException {
    List<Field> result = new ArrayList<>();
    try {
      Object[] components = (Object[]) Class.class.getMethod("getRecordComponents").invoke(entry);
      if (components != null)
        for (Object c : components) {
          String name = (String) c.getClass().getMethod("getName").invoke(c);
          result.add(accessible(entry.getDeclaredField(name)));
        }
    } catch (NoSuchMethodException ignored) {
    }
    if (result.isEmpty())
      for (Field f : entry.getDeclaredFields())
        if (!Modifier.isStatic(f.getModifiers())) result.add(accessible(f));
    if (result.size() != types.length)
      throw new NoSuchFieldException("Player-info canonical field count");
    for (int i = 0; i < types.length; i++)
      if (result.get(i).getType() != types[i])
        throw new NoSuchFieldException("Player-info canonical field order");
    return result;
  }

  private static <T extends AccessibleObject> T accessible(T object) {
    object.setAccessible(true);
    return object;
  }
}
