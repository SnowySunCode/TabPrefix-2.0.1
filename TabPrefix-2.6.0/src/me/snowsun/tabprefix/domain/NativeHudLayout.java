package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.util.*;

/** Delivered private bitmap profiles. Ordinary characters and digits are never replaced. */
public final class NativeHudLayout {
  public static final int FIRST = 0xf0000, STRIDE = 1024, SPACE = 0xff000;
  public static final String ALPHABET;

  static {
    StringBuilder s = new StringBuilder();
    for (int[] range : new int[][] {{33, 126}, {160, 383}, {1024, 1327}, {8192, 8303}})
      for (int c = range[0]; c <= range[1]; c++) s.append((char) c);
    s.append("★☆♡♥✦✧◆◇●○■□▪▫▲▼↑↓←→✓✕⚔⚙");
    ALPHABET = s.toString();
  }

  public static final NativeHudLayout EMPTY = new NativeHudLayout(Collections.emptyMap());

  public static final class Profile {
    public final String id, image;
    public final int base, y, height;
    private final int[] advances;

    public Profile(String id, String image, int base, int y, int height, int[] advances) {
      this.id = Objects.requireNonNull(id);
      this.image = Objects.requireNonNull(image);
      this.base = base;
      this.y = y;
      this.height = height;
      this.advances = advances.clone();
      if (base < FIRST
          || base + advances.length >= SPACE
          || advances.length == 0
          || advances.length > STRIDE
          || y < -512
          || y > 512
          || height < 4
          || height > 32) throw new IllegalArgumentException("Invalid HUD profile");
      for (int a : advances)
        if (a < 1 || a > 512) throw new IllegalArgumentException("Invalid HUD advance");
    }

    public boolean matches(int y, int height, String image) {
      return this.y == y && this.height == height && this.image.equals(image);
    }

    public int frames() {
      return advances.length;
    }

    public int advance(int index) {
      return advances[index];
    }

    public String glyph(int index) {
      if (index < 0 || index >= frames()) throw new IllegalArgumentException("Invalid HUD glyph");
      return new String(Character.toChars(base + index));
    }

    private JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("id", id);
      j.addProperty("image", image);
      j.addProperty("base", base);
      j.addProperty("y", y);
      j.addProperty("height", height);
      JsonArray a = new JsonArray();
      for (int n : advances) a.add(n);
      j.add("advances", a);
      return j;
    }
  }

  public final Map<String, Profile> profiles;

  public NativeHudLayout(Map<String, Profile> profiles) {
    if (profiles.size() > 30) throw new IllegalArgumentException("Too many HUD profiles");
    this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
  }

  public boolean present() {
    return !profiles.isEmpty();
  }

  public Profile get(String id, int y, int height, String image) {
    Profile p = profiles.get(id);
    return p != null && p.matches(y, height, image) ? p : null;
  }

  public boolean matches(DisplayDesign d) {
    boolean needed = false;
    if (d.screenEnabled)
      for (DisplayDesign.Screen s : d.screen)
        if (s.enabled && s.anchor.equals("FREE_XY")) {
          needed = true;
          if (get("screen:" + s.id, s.y, s.height, s.image == null ? "" : s.image.toString())
              == null) return false;
        }
    if (d.sidebarEnabled && d.sidebarMode.equals("HUD")) {
      needed = true;
      if (get("sidebar:title", d.sidebarY, 8, "") == null) return false;
      for (int n = 0; n < d.sidebar.size(); n++)
        if (get("sidebar:" + n, d.sidebarY + 12 + n * 10, 8, "") == null) return false;
    }
    return !needed || get("action", 0, 8, "") != null;
  }

  public JsonObject json() {
    JsonObject j = new JsonObject();
    j.addProperty("schema", 1);
    JsonArray a = new JsonArray();
    for (Profile p : profiles.values()) a.add(p.json());
    j.add("profiles", a);
    return j;
  }

  public static NativeHudLayout read(JsonObject j) {
    if (j.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unknown HUD schema");
    Map<String, Profile> rows = new LinkedHashMap<>();
    for (JsonElement e : j.getAsJsonArray("profiles")) {
      JsonObject p = e.getAsJsonObject();
      JsonArray a = p.getAsJsonArray("advances");
      int[] widths = new int[a.size()];
      for (int n = 0; n < widths.length; n++) widths[n] = a.get(n).getAsInt();
      Profile row =
          new Profile(
              p.get("id").getAsString(),
              p.get("image").getAsString(),
              p.get("base").getAsInt(),
              p.get("y").getAsInt(),
              p.get("height").getAsInt(),
              widths);
      if (rows.put(row.id, row) != null)
        throw new IllegalArgumentException("Duplicate HUD profile");
    }
    return new NativeHudLayout(rows);
  }

  public static String shift(int amount) {
    if (Math.abs((long) amount) > 65536) throw new IllegalArgumentException("HUD text too wide");
    StringBuilder s = new StringBuilder();
    int n = Math.abs(amount), sign = amount < 0 ? 13 : 0;
    while (n > 0) {
      int bit = Math.min(12, 31 - Integer.numberOfLeadingZeros(n));
      s.appendCodePoint(SPACE + sign + bit);
      n -= 1 << bit;
    }
    return s.toString();
  }

  public static final class Encoded {
    public final String text;
    public final int width;

    public Encoded(String text, int width) {
      this.text = text;
      this.width = width;
    }
  }

  public static Encoded encode(Profile p, String legacy) {
    if (!p.image.isEmpty()) throw new IllegalArgumentException("Expected a text profile");
    StringBuilder out = new StringBuilder(), state = new StringBuilder();
    int width = 0;
    boolean bold = false;
    for (int n = 0; n < legacy.length(); ) {
      int c = legacy.codePointAt(n);
      n += Character.charCount(c);
      if (c == '§' && n < legacy.length()) {
        char style = Character.toLowerCase(legacy.charAt(n++));
        if (style == 'x' && n + 12 <= legacy.length()) {
          String rgb = legacy.substring(n, n + 12);
          if (rgb.matches("(?:§[0-9a-fA-F]){6}")) {
            bold = false;
            state.setLength(0);
            state.append("§x").append(rgb);
            out.append(state);
            n += 12;
            continue;
          }
        }
        if (style == 'k' || style == 'm' || style == 'n') continue;
        if (style == 'r' || "0123456789abcdef".indexOf(style) >= 0) {
          bold = false;
          state.setLength(0);
        }
        if (style == 'l') bold = true;
        out.append('§').append(style);
        state.append('§').append(style);
        continue;
      }
      if (c == ' ') {
        int w = Math.max(2, p.height / 2);
        out.append("§r").append(shift(w)).append("§r").append(state);
        width += w;
        continue;
      }
      int i = ALPHABET.indexOf(c <= 65535 ? (char) c : '?');
      if (i < 0) i = ALPHABET.indexOf('?');
      out.append(p.glyph(i));
      width += p.advance(i) + (bold ? 1 : 0);
    }
    return new Encoded(out.toString(), width);
  }

  /** Each block ends at its original cursor position, so the combined action bar stays centered. */
  public static String position(Encoded e, int x) {
    return "§r" + shift(x) + e.text + "§r" + shift(-x - e.width) + "§r";
  }
}
