package me.snowsun.tabprefix.presentation.display;

import me.snowsun.tabprefix.domain.AssetDescriptor;

public final class ChatPrefix {
  public final String text, graphic;
  public final AssetDescriptor asset;

  public ChatPrefix(String text, String graphic, AssetDescriptor asset) {
    this.text = text;
    this.graphic = graphic;
    this.asset = asset;
  }
}
