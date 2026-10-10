package me.snowsun.tabprefix.domain;

import com.google.gson.*;
import java.util.*;

/** Validated, detached display document. Collections cannot change after publication. */
public final class DisplayDesign {
  public final String serverName, sort, title, nameTagPrefix, nameTagSuffix, visibility, collision;
  public final boolean headerEnabled,
      footerEnabled,
      sidebarEnabled,
      layoutEnabled,
      nameTagsEnabled,
      bossBarsEnabled;
  public final int refreshTicks, sidebarX, sidebarY;
  public final String sidebarMode;
  public final String bossBarMode;
  public final int bossBarRotateTicks;
  public final List<Boss> bossBars;
  public final boolean screenEnabled;
  public final List<Screen> screen;
  public final List<Line> header, footer, sidebar;
  public final List<Slot> slots;
  public final boolean tabFormatEnabled,
      nativeSortEnabled,
      sortReverse,
      suffixEnabled,
      luckSuffixFallback;
  public final Line tabPlayerFormat;
  public final List<GroupStyle> groupStyles;

  public static final class GroupStyle {
    public final String group, nameColor, suffixMode;
    public final Line suffix;

    public GroupStyle(JsonObject j) {
      group = GroupTextPrefix.normalizeGroup(string(j, "group", "", 128));
      if (!group.matches("[a-z0-9_.-]+")) throw bad("groupStyle.group: invalid group name");
      nameColor = string(j, "nameColor", "DEFAULT", 16);
      if (!nameColor.equals("DEFAULT") && !nameColor.matches("#[0-9a-fA-F]{6}"))
        throw bad("groupStyle.nameColor: use DEFAULT or #RRGGBB");
      suffixMode = choice(j, "suffixMode", "CUSTOM", "CUSTOM", "LUCKPERMS", "NONE");
      suffix = new Line(j.has("suffix") ? object(j.get("suffix"), "suffix") : new JsonObject());
    }

    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("group", group);
      j.addProperty("nameColor", nameColor);
      j.addProperty("suffixMode", suffixMode);
      j.add("suffix", suffix.json());
      return j;
    }
  }

  public GroupStyle groupStyle(String group) {
    for (GroupStyle style : groupStyles) if (style.group.equalsIgnoreCase(group)) return style;
    return null;
  }

  public int groupRank(String group) {
    for (int i = 0; i < groupStyles.size(); i++)
      if (groupStyles.get(i).group.equalsIgnoreCase(group)) return i;
    return groupStyles.size();
  }

  public static final class Line {
    public final String text, animation;
    public final int speed, width, gap;
    public final List<String> frames;

    public Line(JsonObject j) {
      text = string(j, "text", "", 512);
      animation = choice(j, "animation", "NONE", "NONE", "SCROLL", "FRAMES");
      speed = integer(j, "speed", 6, 2, 200);
      width = integer(j, "width", 28, 4, 80);
      gap = integer(j, "gap", 5, 1, 20);
      List<String> f = new ArrayList<>();
      if (j.has("frames")) {
        if (!j.get("frames").isJsonArray()) throw bad("frames");
        if (j.getAsJsonArray("frames").size() > 32) throw bad("frames: maximum 32");
        for (JsonElement e : j.getAsJsonArray("frames")) {
          if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw bad("frame");
          f.add(text(e.getAsString(), 512, "frame"));
        }
      }
      if (animation.equals("FRAMES") && f.isEmpty()) throw bad("Add at least one animation frame");
      frames = Collections.unmodifiableList(f);
    }

    public String source(long tick, boolean animate) {
      return animation.equals("FRAMES")
          ? frames.get(animate ? (int) ((tick / speed) % frames.size()) : 0)
          : text;
    }

    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("text", text);
      j.addProperty("animation", animation);
      j.addProperty("speed", speed);
      j.addProperty("width", width);
      j.addProperty("gap", gap);
      JsonArray a = new JsonArray();
      for (String f : frames) a.add(f);
      j.add("frames", a);
      return j;
    }
  }

  public static final class Slot {
    public final String kind;
    public final UUID image;
    public final int playerIndex;
    public final Line line;

    public Slot(JsonObject j) {
      kind = choice(j, "kind", "EMPTY", "EMPTY", "TEXT", "PLAYER", "IMAGE");
      image = kind.equals("IMAGE") ? UUID.fromString(string(j, "image", "", 36)) : null;
      playerIndex = integer(j, "playerIndex", 1, 1, 80);
      line = new Line(j.has("line") ? object(j.get("line"), "line") : new JsonObject());
    }

    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("kind", kind);
      j.addProperty("image", image == null ? "" : image.toString());
      j.addProperty("playerIndex", playerIndex);
      j.add("line", line.json());
      return j;
    }
  }

  public static final class Screen {
    public final String id, anchor, kind, permission;
    public final int x, y, height;
    public final UUID image;
    public final boolean enabled;
    public final Line line;
    public final List<String> worlds;

    public Screen(JsonObject j) {
      Boss options = new Boss(j);
      id = options.id;
      permission = options.permission;
      enabled = options.enabled;
      line = options.line;
      worlds = options.worlds;
      anchor = choice(j, "anchor", "TITLE", "TITLE", "SUBTITLE", "ACTION_BAR", "FREE_XY");
      x = integer(j, "x", 0, -512, 512);
      y = integer(j, "y", -60, -512, 512);
      height = integer(j, "height", 8, 4, 32);
      kind = choice(j, "kind", "TEXT", "TEXT", "IMAGE");
      image = kind.equals("IMAGE") ? UUID.fromString(string(j, "image", "", 36)) : null;
    }

    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("id", id);
      j.addProperty("anchor", anchor);
      j.addProperty("x", x);
      j.addProperty("y", y);
      j.addProperty("height", height);
      j.addProperty("kind", kind);
      j.addProperty("image", image == null ? "" : image.toString());
      j.addProperty("enabled", enabled);
      j.add("line", line.json());
      j.addProperty("permission", permission);
      JsonArray a = new JsonArray();
      for (String world : worlds) a.add(world);
      j.add("worlds", a);
      return j;
    }
  }

  /** Native Minecraft bar options; every bar keeps a stable identity when reordered. */
  public static final class Boss {
    public final String id, color, style, progressMode, progressSource, permission;
    public final boolean enabled, loop, hideAfter, darkenSky, playMusic, createFog;
    public final double progress, progressMax;
    public final int durationTicks;
    public final Line line;
    public final List<String> worlds;

    public Boss(JsonObject j) {
      id = string(j, "id", "", 48);
      if (!id.matches("[A-Za-z0-9_-]{1,48}"))
        throw bad("bossBar.id: use a unique stable identifier");
      enabled = bool(j, "enabled", true);
      color =
          choice(j, "color", "PURPLE", "PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
      style =
          choice(
              j,
              "style",
              "SOLID",
              "SOLID",
              "SEGMENTED_6",
              "SEGMENTED_10",
              "SEGMENTED_12",
              "SEGMENTED_20");
      line = new Line(j.has("line") ? object(j.get("line"), "bossBar.line") : new JsonObject());
      progressMode =
          choice(
              j,
              "progressMode",
              "STATIC",
              "STATIC",
              "HEALTH",
              "FOOD",
              "EXPERIENCE",
              "ONLINE",
              "CUSTOM",
              "FILL",
              "DRAIN",
              "PULSE");
      progress = decimal(j, "progress", 100, 0, 100);
      progressSource =
          choice(
              j,
              "progressSource",
              "online",
              "online",
              "max",
              "health",
              "max_health",
              "food",
              "experience",
              "level",
              "ping",
              "x",
              "y",
              "z",
              "layout_overflow");
      progressMax = decimal(j, "progressMax", 100, 0.01, 1000000000);
      durationTicks = integer(j, "durationTicks", 200, 20, 72000);
      loop = bool(j, "loop", true);
      hideAfter = bool(j, "hideAfter", false);
      darkenSky = bool(j, "darkenSky", false);
      playMusic = bool(j, "playMusic", false);
      createFog = bool(j, "createFog", false);
      permission = string(j, "permission", "", 128);
      if (!permission.matches("[A-Za-z0-9_.*-]*"))
        throw bad("bossBar.permission: invalid permission node");
      List<String> worlds = new ArrayList<>();
      if (j.has("worlds")) {
        if (!j.get("worlds").isJsonArray() || j.getAsJsonArray("worlds").size() > 16)
          throw bad("bossBar.worlds: maximum 16 worlds");
        for (JsonElement e : j.getAsJsonArray("worlds")) {
          if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw bad("bossBar.worlds: expected world names");
          String world = text(e.getAsString(), 128, "bossBar.world");
          if (world.trim().isEmpty()) throw bad("bossBar.world: empty name");
          if (!worlds.contains(world)) worlds.add(world);
        }
      }
      this.worlds = Collections.unmodifiableList(worlds);
    }

    public boolean timed() {
      return progressMode.equals("FILL")
          || progressMode.equals("DRAIN")
          || progressMode.equals("PULSE");
    }

    public JsonObject json() {
      JsonObject j = new JsonObject();
      j.addProperty("id", id);
      j.addProperty("enabled", enabled);
      j.addProperty("color", color);
      j.addProperty("style", style);
      j.add("line", line.json());
      j.addProperty("progressMode", progressMode);
      j.addProperty("progress", progress);
      j.addProperty("progressSource", progressSource);
      j.addProperty("progressMax", progressMax);
      j.addProperty("durationTicks", durationTicks);
      j.addProperty("loop", loop);
      j.addProperty("hideAfter", hideAfter);
      j.addProperty("darkenSky", darkenSky);
      j.addProperty("playMusic", playMusic);
      j.addProperty("createFog", createFog);
      j.addProperty("permission", permission);
      JsonArray a = new JsonArray();
      for (String world : worlds) a.add(world);
      j.add("worlds", a);
      return j;
    }
  }

  public DisplayDesign(JsonObject j) {
    if (j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000)
      throw bad("Display document exceeds 60 KB");
    integer(j, "schema", 1, 1, 1);
    serverName = string(j, "serverName", "Minecraft", 64);
    refreshTicks = integer(j, "refreshTicks", 4, 2, 200);
    sort = choice(j, "sort", "NAME", "NAME", "GROUP", "PRIORITY", "WEIGHT", "PING", "WORLD");
    sortReverse = bool(j, "sortReverse", false);
    tabFormatEnabled = bool(j, "tabFormatEnabled", false);
    nativeSortEnabled = bool(j, "nativeSortEnabled", false);
    suffixEnabled = bool(j, "suffixEnabled", true);
    luckSuffixFallback = bool(j, "luckSuffixFallback", true);
    JsonObject defaultPlayer = new JsonObject();
    defaultPlayer.addProperty("text", "{prefix}{display_name}{suffix}");
    tabPlayerFormat =
        new Line(
            j.has("tabPlayerFormat")
                ? object(j.get("tabPlayerFormat"), "tabPlayerFormat")
                : defaultPlayer);
    List<GroupStyle> styles = new ArrayList<>();
    Set<String> groups = new HashSet<>();
    if (j.has("groupStyles")) {
      if (!j.get("groupStyles").isJsonArray() || j.getAsJsonArray("groupStyles").size() > 64)
        throw bad("groupStyles: maximum 64 groups");
      for (JsonElement e : j.getAsJsonArray("groupStyles")) {
        GroupStyle style = new GroupStyle(object(e, "groupStyle"));
        if (!groups.add(style.group)) throw bad("groupStyles: duplicate group");
        styles.add(style);
      }
    }
    groupStyles = Collections.unmodifiableList(styles);
    headerEnabled = bool(j, "headerEnabled", false);
    footerEnabled = bool(j, "footerEnabled", false);
    sidebarEnabled = bool(j, "sidebarEnabled", false);
    sidebarMode = choice(j, "sidebarMode", "NATIVE", "NATIVE", "HUD", "TAB");
    sidebarX = integer(j, "sidebarX", 72, -512, 512);
    sidebarY = integer(j, "sidebarY", -110, -512, 352);
    layoutEnabled = bool(j, "layoutEnabled", false);
    nameTagsEnabled = bool(j, "nameTagsEnabled", false);
    bossBarsEnabled = bool(j, "bossBarsEnabled", false);
    bossBarMode = choice(j, "bossBarMode", "STACKED", "STACKED", "ROTATE");
    bossBarRotateTicks = integer(j, "bossBarRotateTicks", 200, 20, 72000);
    List<Boss> bars = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    if (j.has("bossBars")) {
      if (!j.get("bossBars").isJsonArray() || j.getAsJsonArray("bossBars").size() > 8)
        throw bad("bossBars: maximum 8 bars");
      for (JsonElement e : j.getAsJsonArray("bossBars")) {
        Boss bar = new Boss(object(e, "bossBar"));
        if (!ids.add(bar.id)) throw bad("bossBars: duplicate identifier");
        bars.add(bar);
      }
    }
    bossBars = Collections.unmodifiableList(bars);
    screenEnabled = bool(j, "screenEnabled", false);
    List<Screen> elements = new ArrayList<>();
    Set<String> screenIds = new HashSet<>();
    if (j.has("screen")) {
      if (!j.get("screen").isJsonArray() || j.getAsJsonArray("screen").size() > 12)
        throw bad("screen: maximum 12 elements");
      for (JsonElement e : j.getAsJsonArray("screen")) {
        Screen item = new Screen(object(e, "screen element"));
        if (!screenIds.add(item.id)) throw bad("screen: duplicate identifier");
        elements.add(item);
      }
    }
    screen = Collections.unmodifiableList(elements);
    title = string(j, "title", "<aqua>{server}</aqua>", 256);
    nameTagPrefix = string(j, "nameTagPrefix", "{prefix}", 256);
    nameTagSuffix = string(j, "nameTagSuffix", "", 256);
    visibility =
        choice(j, "visibility", "ALWAYS", "ALWAYS", "NEVER", "FOR_OTHER_TEAMS", "FOR_OWN_TEAM");
    collision =
        choice(j, "collision", "ALWAYS", "ALWAYS", "NEVER", "FOR_OTHER_TEAMS", "FOR_OWN_TEAM");
    header = lines(j, "header", 12);
    footer = lines(j, "footer", 12);
    sidebar = lines(j, "sidebar", 15);
    List<Slot> cells = new ArrayList<>();
    if (!j.has("slots") || !j.get("slots").isJsonArray() || j.getAsJsonArray("slots").size() != 80)
      throw bad("TAB layout must contain exactly 80 slots (4 columns x 20 rows)");
    for (JsonElement e : j.getAsJsonArray("slots")) cells.add(new Slot(object(e, "slot")));
    slots = Collections.unmodifiableList(cells);
  }

  public JsonObject json() {
    JsonObject j = new JsonObject();
    j.addProperty("screenEnabled", screenEnabled);
    JsonArray screens = new JsonArray();
    for (Screen item : screen) screens.add(item.json());
    j.add("screen", screens);
    j.addProperty("schema", 1);
    j.addProperty("serverName", serverName);
    j.addProperty("refreshTicks", refreshTicks);
    j.addProperty("sort", sort);
    j.addProperty("sortReverse", sortReverse);
    j.addProperty("tabFormatEnabled", tabFormatEnabled);
    j.addProperty("nativeSortEnabled", nativeSortEnabled);
    j.addProperty("suffixEnabled", suffixEnabled);
    j.addProperty("luckSuffixFallback", luckSuffixFallback);
    j.add("tabPlayerFormat", tabPlayerFormat.json());
    JsonArray styles = new JsonArray();
    for (GroupStyle style : groupStyles) styles.add(style.json());
    j.add("groupStyles", styles);
    j.addProperty("headerEnabled", headerEnabled);
    j.addProperty("footerEnabled", footerEnabled);
    j.addProperty("sidebarEnabled", sidebarEnabled);
    j.addProperty("sidebarMode", sidebarMode);
    j.addProperty("sidebarX", sidebarX);
    j.addProperty("sidebarY", sidebarY);
    j.addProperty("layoutEnabled", layoutEnabled);
    j.addProperty("nameTagsEnabled", nameTagsEnabled);
    j.addProperty("bossBarsEnabled", bossBarsEnabled);
    j.addProperty("bossBarMode", bossBarMode);
    j.addProperty("bossBarRotateTicks", bossBarRotateTicks);
    JsonArray bars = new JsonArray();
    for (Boss bar : bossBars) bars.add(bar.json());
    j.add("bossBars", bars);
    j.addProperty("title", title);
    j.addProperty("nameTagPrefix", nameTagPrefix);
    j.addProperty("nameTagSuffix", nameTagSuffix);
    j.addProperty("visibility", visibility);
    j.addProperty("collision", collision);
    j.add("header", array(header));
    j.add("footer", array(footer));
    j.add("sidebar", array(sidebar));
    JsonArray a = new JsonArray();
    for (Slot cell : slots) a.add(cell.json());
    j.add("slots", a);
    return j;
  }

  public static DisplayDesign defaults(boolean nameTags) {
    JsonObject j = new JsonObject();
    j.addProperty("nameTagsEnabled", nameTags);
    j.addProperty("title", "<aqua><bold>{server}</bold></aqua>");
    JsonArray header = new JsonArray();
    header.add(line("<aqua><bold>{server}</bold></aqua>"));
    header.add(line("<gray>Welcome, <white>{player}</white> · {online}/{max} online</gray>"));
    j.add("header", header);
    JsonArray footer = new JsonArray();
    footer.add(line("<gray>{world} · <green>{ping} ms</green></gray>"));
    j.add("footer", footer);
    JsonArray sidebar = new JsonArray();
    for (String s :
        Arrays.asList(
            "",
            "<gray>Player <white>{player}</white></gray>",
            "<gray>Group <aqua>{group}</aqua></gray>",
            "",
            "<gray>Online <green>{online}/{max}</green></gray>",
            "<gray>World <white>{world}</white></gray>",
            "<gray>Ping <green>{ping} ms</green></gray>",
            "",
            "<aqua>{server}</aqua>")) sidebar.add(line(s));
    j.add("sidebar", sidebar);
    JsonArray slots = new JsonArray();
    for (int i = 0; i < 80; i++) {
      JsonObject cell = new JsonObject();
      cell.addProperty("kind", "PLAYER");
      cell.addProperty("playerIndex", i + 1);
      cell.add("line", line("{prefix}{player}"));
      slots.add(cell);
    }
    j.add("slots", slots);
    return new DisplayDesign(j);
  }

  private static JsonObject line(String text) {
    JsonObject j = new JsonObject();
    j.addProperty("text", text);
    return j;
  }

  private static JsonArray array(List<Line> lines) {
    JsonArray a = new JsonArray();
    for (Line line : lines) a.add(line.json());
    return a;
  }

  private static List<Line> lines(JsonObject j, String key, int limit) {
    List<Line> out = new ArrayList<>();
    if (j.has(key)) {
      if (!j.get(key).isJsonArray() || j.getAsJsonArray(key).size() > limit)
        throw bad(key + ": maximum " + limit + " lines");
      for (JsonElement e : j.getAsJsonArray(key)) out.add(new Line(object(e, key)));
    }
    return Collections.unmodifiableList(out);
  }

  public static JsonObject object(JsonElement e, String key) {
    if (e == null || !e.isJsonObject()) throw bad(key + " must be an object");
    return e.getAsJsonObject();
  }

  public static boolean bool(JsonObject j, String key, boolean fallback) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
      throw bad(key + " must be boolean");
    return e.getAsBoolean();
  }

  public static int integer(JsonObject j, String key, int fallback, int min, int max) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
      throw bad(key + " must be an integer");
    double v = e.getAsDouble();
    if (!Double.isFinite(v) || v != Math.rint(v) || v < min || v > max)
      throw bad(key + " must be " + min + ".." + max);
    return (int) v;
  }

  private static double decimal(JsonObject j, String key, double fallback, double min, double max) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
      throw bad(key + " must be a number");
    double value = e.getAsDouble();
    if (!Double.isFinite(value) || value < min || value > max)
      throw bad(key + " must be " + min + ".." + max);
    return value;
  }

  private static String string(JsonObject j, String key, String fallback, int max) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
      throw bad(key + " must be text");
    return text(e.getAsString(), max, key);
  }

  private static String text(String s, int max, String key) {
    if (s.length() > max) throw bad(key + ": maximum " + max + " characters");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isISOControl(c)) throw bad(key + ": control characters are not allowed");
      if (Character.isHighSurrogate(c)) {
        if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
          throw bad(key + ": invalid Unicode");
      } else if (Character.isLowSurrogate(c)) throw bad(key + ": invalid Unicode");
    }
    return s;
  }

  private static String choice(JsonObject j, String key, String fallback, String... allowed) {
    String value = string(j, key, fallback, 64);
    if (!Arrays.asList(allowed).contains(value)) throw bad(key + ": unsupported value");
    return value;
  }

  private static IllegalArgumentException bad(String s) {
    return new IllegalArgumentException(s);
  }
}
