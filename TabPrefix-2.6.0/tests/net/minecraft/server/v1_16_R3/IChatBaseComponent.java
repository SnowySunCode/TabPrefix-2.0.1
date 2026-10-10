package net.minecraft.server.v1_16_R3;

public final class IChatBaseComponent {
  public final String json;

  private IChatBaseComponent(String json) {
    this.json = json;
  }

  public static final class ChatSerializer {
    public static IChatBaseComponent a(String json) {
      return new IChatBaseComponent(json);
    }
  }
}
