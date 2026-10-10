package net.minecraft.server.v1_16_R3;

import com.mojang.authlib.GameProfile;
import java.util.*;

public final class PacketPlayOutPlayerInfo implements Packet {
  public enum EnumPlayerInfoAction {
    ADD_PLAYER,
    UPDATE_GAME_MODE,
    UPDATE_LATENCY,
    UPDATE_DISPLAY_NAME,
    REMOVE_PLAYER
  }

  public final EnumPlayerInfoAction action;
  public final List<PlayerInfoData> entries = new ArrayList<>();

  public PacketPlayOutPlayerInfo(EnumPlayerInfoAction action, EntityPlayer... players) {
    this.action = action;
    for (EntityPlayer p : players)
      entries.add(
          new PlayerInfoData(
              p.getProfile(), p.ping, p.gameMode, IChatBaseComponent.ChatSerializer.a("{}")));
  }

  public final class PlayerInfoData {
    public final GameProfile profile;
    public final int ping;
    private EnumGamemode mode;
    public final IChatBaseComponent display;

    public PlayerInfoData(
        GameProfile profile, int ping, EnumGamemode mode, IChatBaseComponent display) {
      this.profile = profile;
      this.ping = ping;
      this.mode = mode;
      this.display = display;
    }
  }
}
