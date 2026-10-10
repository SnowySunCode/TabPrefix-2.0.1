package net.minecraft.server.v1_16_R3;

import com.mojang.authlib.GameProfile;

public final class EntityPlayer {
  public final PlayerConnection playerConnection = new PlayerConnection();
  public int ping = 42;
  public final EnumGamemode gameMode = EnumGamemode.CREATIVE;
  private final GameProfile profile;

  public EntityPlayer(GameProfile profile) {
    this.profile = profile;
  }

  public GameProfile getProfile() {
    return profile;
  }
}
