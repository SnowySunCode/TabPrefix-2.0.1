package me.snowsun.tabprefix.presentation.display;

import java.util.*;

/** Moves full Unicode code points, carrying colour and decoration without splitting RGB codes. */
public final class DisplayAnimation {
  private DisplayAnimation() {}

  public static String scroll(String legacy, int width, int gap, long step) {
    List<String> glyphs = new ArrayList<>(), styles = new ArrayList<>();
    String color = "", decorations = "";
    for (int i = 0; i < legacy.length(); ) {
      if (legacy.charAt(i) == '§' && i + 1 < legacy.length()) {
        char code = Character.toLowerCase(legacy.charAt(i + 1));
        if (code == 'x'
            && i + 14 <= legacy.length()
            && legacy.substring(i, i + 14).matches("§[xX](§[0-9a-fA-F]){6}")) {
          color = legacy.substring(i, i + 14);
          decorations = "";
          i += 14;
        } else {
          if (code == 'r') {
            color = "";
            decorations = "";
          } else if ("0123456789abcdef".indexOf(code) >= 0) {
            color = legacy.substring(i, i + 2);
            decorations = "";
          } else if ("klmno".indexOf(code) >= 0) decorations += legacy.substring(i, i + 2);
          i += 2;
        }
      } else {
        int cp = legacy.codePointAt(i);
        glyphs.add(new String(Character.toChars(cp)));
        styles.add(color + decorations);
        i += Character.charCount(cp);
      }
    }
    if (glyphs.size() <= width) return legacy;
    for (int i = 0; i < gap; i++) {
      glyphs.add(" ");
      styles.add("");
    }
    int offset = (int) Math.floorMod(step, (long) glyphs.size());
    StringBuilder result = new StringBuilder();
    String previous = null;
    for (int i = 0; i < width; i++) {
      int index = (offset + i) % glyphs.size();
      String style = styles.get(index);
      if (!style.equals(previous)) {
        result.append("§r").append(style);
        previous = style;
      }
      result.append(glyphs.get(index));
    }
    return result.append("§r").toString();
  }
}
