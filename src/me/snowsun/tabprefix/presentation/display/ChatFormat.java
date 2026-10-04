package me.snowsun.tabprefix.presentation.display;

/** Prefixes are literal text; percent signs cannot become String.format directives. */
public final class ChatFormat {
  private ChatFormat() {}

  public static String prepend(String prefix, String existingFormat) {
    return prefix.isEmpty() ? existingFormat : prefix.replace("%", "%%") + "§r" + existingFormat;
  }
}
