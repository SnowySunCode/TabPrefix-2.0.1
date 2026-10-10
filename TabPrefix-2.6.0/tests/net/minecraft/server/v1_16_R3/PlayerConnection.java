package net.minecraft.server.v1_16_R3;

import java.util.*;

public final class PlayerConnection {
  public final List<Packet> packets = new ArrayList<>();
  public boolean fail;

  public void sendPacket(Packet packet) {
    if (fail) throw new IllegalStateException("Injected connection failure");
    packets.add(packet);
  }
}
