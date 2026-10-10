package me.snowsun.tabprefix.domain;

/** Explicit storage format; LuckPerms metadata is interpreted as legacy text. */
public enum TextFormat {
  LEGACY,
  MINIMESSAGE;

  public static TextFormat detect(String text) {
    return text.matches("(?s).*<[a-zA-Z#/!].*") ? MINIMESSAGE : LEGACY;
  }
}
