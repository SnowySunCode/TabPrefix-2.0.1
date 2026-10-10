#!/usr/bin/env python3
"""Independent packet-shape fixtures. No Minecraft/CraftBukkit implementation is redistributed.

Fixtures exercise pre-1.17 packages, unversioned Spigot packets, immutable records (7/8/9
components), inherited connections and the immutable GameProfile/PropertyMap shape.
They are contract tests, not substitutes for running a Minecraft server with a real client.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])


def write(family, path, content):
    target = root / family / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def java(family, pkg, name, body):
    write(family, pkg.replace(".", "/") + "/" + name + ".java", "package " + pkg + ";\n" + body)


for family, revision in [("legacy12", "v1_12_R1"), ("legacy13", "v1_13_R2"), ("legacy14", "v1_14_R1"), ("legacy15", "v1_15_R1"), ("spigot17", None), ("spigot19", None), ("update7", None), ("update8", None), ("update9", None), ("mojang26", None)]:
    old = revision is not None
    modern = family.startswith("update") or family == "mojang26"
    mojang = family in ("update9", "mojang26")
    nms = "net.minecraft.server." + revision if old else "net.minecraft.network.protocol.game"
    protocol = nms if old else "net.minecraft.network.protocol"
    chat = nms if old else "net.minecraft.network.chat"
    component = "IChatBaseComponent" if old or not mojang else "Component"
    level = nms if old else "net.minecraft.world.level"
    mode = "GameType" if mojang else "EnumGamemode"
    entities = nms if old else "net.minecraft.server.level"
    entity = "ServerPlayer" if mojang else "EntityPlayer"
    connection_pkg = nms if old else "net.minecraft.server.network"
    connection = "ServerGamePacketListenerImpl" if mojang else "PlayerConnection"
    send = "send" if mojang else "sendPacket"
    player_packet = "ClientboundPlayerInfoUpdatePacket" if modern else "PacketPlayOutPlayerInfo"
    java(family, protocol, "Packet", "public interface Packet {}\n")
    java(family, chat, component, """public final class @@COMP@@ {
      public final String json;
      public @@COMP@@(String json) { this.json=json; }
      public String toString() { return json; }
      public static final class ChatSerializer { public static @@COMP@@ a(String json) { return new @@COMP@@(json); } }
    }
    """.replace("@@COMP@@", component))
    java(family, level, mode, "public enum " + mode + " {SURVIVAL,CREATIVE,SPECTATOR}\n")
    java(family, connection_pkg, "PacketSendBase", """import java.util.*;
      public class PacketSendBase {
        public final List<Object> packets=new ArrayList<>(); public boolean fail;
        public void @@SEND@@(@@PACKET@@ packet) { if(fail)throw new IllegalStateException("fixture send failed");packets.add(packet); }
      }
    """.replace("@@SEND@@", send).replace("@@PACKET@@", protocol + ".Packet"))
    java(family, connection_pkg, connection, "public final class " + connection + " extends PacketSendBase {}\n")
    java(family, entities, "PlayerBase", """public class PlayerBase {
      public final @@CONNECTION@@ @@FIELD@@=new @@CONNECTION@@();
    }
    """.replace("@@CONNECTION@@", connection_pkg + "." + connection).replace("@@FIELD@@", "connection" if mojang else "playerConnection"))
    java(family, entities, entity, """import com.mojang.authlib.GameProfile;
    public final class @@ENTITY@@ extends PlayerBase {
      public int ping=42; public final @@MODE@@ gameMode=@@MODE@@.SPECTATOR;
      private final GameProfile profile;
      public @@ENTITY@@(GameProfile profile){this.profile=profile;}
      public GameProfile @@PROFILE@@(){return profile;}
    }
    """.replace("@@ENTITY@@", entity).replace("@@MODE@@", level + "." + mode).replace("@@PROFILE@@", "getGameProfile" if mojang else "getProfile"))
    java(family, "compat.fixture", "CraftPlayer", "public interface CraftPlayer extends org.bukkit.entity.Player { " + entities + "." + entity + " getHandle(); }\n")
    if not old:
        java(family, "org.bukkit.craftbukkit.util", "CraftChatMessage", "public final class CraftChatMessage { public static " + chat + "." + component + " fromJSON(String json) { return new " + chat + "." + component + "(json); } }\n")
    imports = "import java.util.*;import com.mojang.authlib.GameProfile;import " + entities + "." + entity + ";import " + level + "." + mode + ";import " + chat + "." + component + ";import " + protocol + ".Packet;\n"
    if not modern:
        java(family, nms, player_packet, imports + """public final class PacketPlayOutPlayerInfo implements Packet {
          public enum EnumPlayerInfoAction { ADD_PLAYER,UPDATE_GAME_MODE,UPDATE_LATENCY,UPDATE_DISPLAY_NAME,REMOVE_PLAYER }
          public final EnumPlayerInfoAction action; private final List<PlayerInfoData> entries=new ArrayList<>();
          public PacketPlayOutPlayerInfo(EnumPlayerInfoAction action,@@ENTITY@@...players){this.action=action;for(@@ENTITY@@ p:players)entries.add(new PlayerInfoData(p.getProfile(),p.ping,p.gameMode,new @@COMP@@("Original")@@KEY_FACTORY@@));}
          public @@STATIC@@ final class PlayerInfoData {
            public final GameProfile profile;public final int ping;public final @@MODE@@ gameMode;public final @@COMP@@ display;@@KEY_FIELD@@
            public PlayerInfoData(GameProfile profile,int ping,@@MODE@@ gameMode,@@COMP@@ display@@KEY_PARAMETER@@){this.profile=profile;this.ping=ping;this.gameMode=gameMode;this.display=display;@@KEY_ASSIGN@@}
          }
        }
        """.replace("@@ENTITY@@", entity).replace("@@COMP@@", component).replace("@@MODE@@", mode).replace("@@STATIC@@", "" if old else "static").replace("@@KEY_FACTORY@@", ",new PublicKeyData()" if family == "spigot19" else "").replace("@@KEY_FIELD@@", "public final PublicKeyData keyData;" if family == "spigot19" else "").replace("@@KEY_PARAMETER@@", ",PublicKeyData keyData" if family == "spigot19" else "").replace("@@KEY_ASSIGN@@", "this.keyData=keyData;" if family == "spigot19" else ""))
        if family == "spigot19":
            java(family, nms, "PublicKeyData", 'public final class PublicKeyData {public String toString(){return "real-key";}}\n')
    else:
        args = "UUID a,GameProfile b,boolean c,int d," + mode + " e," + component + " f"
        values = "id,profile,false,42,mode,text"
        extra_values = ""
        if family == "update8":
            args += ",int g"; extra_values = ",600"
        elif mojang:
            args = "UUID profileId,GameProfile profile,boolean listed,int latency," + mode + " gameMode," + component + " displayName,boolean showHat,int listOrder"
            extra_values = ",false,600"
        args += ",Session " + ("chatSession" if mojang else "h" if family == "update8" else "g")
        get_profile = "getGameProfile" if mojang else "getProfile"
        get_id = "id" if family == "mojang26" else "getId"
        java(family, nms, player_packet, imports + """public final class ClientboundPlayerInfoUpdatePacket implements Packet {
          public enum Action { ADD_PLAYER,INITIALIZE_CHAT,UPDATE_GAME_MODE,UPDATE_LISTED,UPDATE_LATENCY,UPDATE_DISPLAY_NAME,UPDATE_LIST_ORDER,UPDATE_HAT }
          public final EnumSet<Action> actions; private final List<Entry> entries;
          public static final class Session {public String toString(){return "real-session";}}
          public record Entry(@@ARGS@@) {}
          public ClientboundPlayerInfoUpdatePacket(EnumSet<Action> actions,Collection<@@ENTITY@@> players) {
            this.actions=actions;List<Entry> values=new ArrayList<>();
            for(@@ENTITY@@ p:players) { GameProfile profile=p.@@PROFILE@@(); UUID id=profile.@@ID@@();@@MODE@@ mode=p.gameMode;@@COMP@@ text=new @@COMP@@("Original");
              values.add(new Entry(@@VALUES@@,new Session())); }
            this.entries=List.copyOf(values);
          }
        }
        """.replace("@@ARGS@@", args).replace("@@ENTITY@@", entity).replace("@@PROFILE@@", get_profile).replace("@@ID@@", get_id).replace("@@MODE@@", mode).replace("@@COMP@@", component).replace("@@VALUES@@", values + extra_values))
        java(family, nms, "ClientboundPlayerInfoRemovePacket", "import java.util.*;import " + protocol + ".Packet;public final class ClientboundPlayerInfoRemovePacket implements Packet {public final List<UUID> ids;public ClientboundPlayerInfoRemovePacket(List<UUID> ids){this.ids=List.copyOf(ids);}}\n")
    header = "PacketPlayOutPlayerListHeaderFooter" if old else "ClientboundTabListPacket"
    java(family, nms, header, "import " + chat + "." + component + ";import " + protocol + ".Packet;public final class " + header + " implements Packet {public " + component + " header,footer;public " + header + "(){}}\n")
    if family == "mojang26":
        java(family, "com.mojang.authlib", "GameProfile", "import java.util.UUID;import com.mojang.authlib.properties.PropertyMap;public record GameProfile(UUID id,String name,PropertyMap properties) {}\n")
        java(family, "com.mojang.authlib.properties", "PropertyMap", "import java.util.*;public final class PropertyMap {public static final PropertyMap EMPTY=new PropertyMap(\"\");private final Map<String,String> values;public PropertyMap(String skin){values=skin.isEmpty()?Map.of():Map.of(\"textures\",skin);}public String toString(){return values.toString();}}\n")
