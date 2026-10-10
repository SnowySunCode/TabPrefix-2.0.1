package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.luckperms.LuckPermsBridge;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.infrastructure.resourcepack.PackBuilder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Reads the generated ZIP with independent glyph metrics; does not emulate the game renderer. */
public final class NativeCanvasIntegrationTest {
  private static int checks;

  private static void check(boolean v, String why) {
    if (!v) throw new AssertionError(why);
    checks++;
  }

  private static void reject(Runnable work) {
    try {
      work.run();
      throw new AssertionError("Invalid canvas input accepted");
    } catch (IllegalArgumentException e) {
      checks++;
    }
  }

  private static JsonObject copy(JsonObject j) {
    return new JsonParser().parse(j.toString()).getAsJsonObject();
  }

  private static JsonObject screen() {
    JsonObject j = new JsonObject();
    j.addProperty("id", "free");
    j.addProperty("kind", "TEXT");
    j.addProperty("anchor", "FREE_XY");
    j.addProperty("x", -30);
    j.addProperty("y", -70);
    j.addProperty("height", 8);
    JsonObject line = new JsonObject();
    line.addProperty("text", "<green>Привет {player} 0123456789</green>");
    j.add("line", line);
    return j;
  }

  private static DisplayDesign design() {
    JsonObject j = DisplayDesign.defaults(false).json();
    j.addProperty("screenEnabled", true);
    j.addProperty("sidebarEnabled", true);
    j.addProperty("sidebarMode", "HUD");
    j.addProperty("sidebarY", -90);
    JsonArray rows = new JsonArray();
    rows.add(screen());
    j.add("screen", rows);
    return new DisplayDesign(j);
  }

  private static PluginSettings settings(Path project, Path root, String version) throws Exception {
    return new PluginSettings(
        YamlConfiguration.loadConfiguration(project.resolve("resources/config.yml").toFile()),
        root,
        MinecraftVersion.parse(version));
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]);
    if (Arrays.asList(args).contains("--standalone")) {
      standalone(project);
      return;
    }
    input();
    for (String v : new String[] {"1.12.2", "1.13.2", "1.18.2", "1.19.4", "1.21.11", "26.3"})
      pack(project, v);
    extremes(project);
    images(project);
    System.out.println(
        "PASS: "
            + checks
            + " native canvas checks: pack geometry, signed spacing, colors, image frames, restart"
            + " and input bounds.");
  }

  private static void input() {
    DisplayDesign d = design();
    DisplayDesign restored = new DisplayDesign(d.json());
    check(
        restored.screen.get(0).x == -30
            && restored.screen.get(0).y == -70
            && restored.screen.get(0).height == 8,
        "coordinate round trip");
    check(restored.sidebarMode.equals("HUD") && restored.sidebarY == -90, "panel round trip");
    for (String field : new String[] {"x", "y", "height"})
      for (JsonPrimitive value :
          new JsonPrimitive[] {
            new JsonPrimitive(3.5), new JsonPrimitive(true), new JsonPrimitive("8")
          }) {
        JsonObject j = d.json();
        j.getAsJsonArray("screen").get(0).getAsJsonObject().add(field, value);
        reject(() -> new DisplayDesign(j));
      }
    for (int x : new int[] {-513, 513}) {
      JsonObject j = d.json();
      j.getAsJsonArray("screen").get(0).getAsJsonObject().addProperty("x", x);
      reject(() -> new DisplayDesign(j));
    }
    for (int h : new int[] {3, 33}) {
      JsonObject j = d.json();
      j.getAsJsonArray("screen").get(0).getAsJsonObject().addProperty("height", h);
      reject(() -> new DisplayDesign(j));
    }
    JsonObject j = d.json();
    j.addProperty("sidebarY", 353);
    reject(() -> new DisplayDesign(j));
    reject(() -> NativeHudLayout.shift(65537));
  }

  private static JsonObject json(ZipFile zip, String path) throws Exception {
    try (InputStream in = zip.getInputStream(zip.getEntry(path));
        Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new JsonParser().parse(r).getAsJsonObject();
    }
  }

  private static final class Metrics {
    final Map<Integer, Integer> widths = new HashMap<>(), ys = new HashMap<>();
  }

  private static Metrics readMetrics(ZipFile zip) throws Exception {
    Metrics result = new Metrics();
    Map<String, BufferedImage> images = new HashMap<>();
    JsonArray providers =
        json(zip, "assets/minecraft/font/default.json").getAsJsonArray("providers");
    for (JsonElement element : providers) {
      JsonObject p = element.getAsJsonObject();
      if (p.get("type").getAsString().equals("space")) {
        for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("advances").entrySet()) {
          int c = e.getKey().codePointAt(0);
          check(c >= NativeHudLayout.SPACE, "only private spacers");
          result.widths.put(c, e.getValue().getAsInt());
        }
        continue;
      }
      if (!p.has("file") || !p.get("file").getAsString().contains("hud-")) continue;
      String file = "assets/tabprefix/textures/" + p.get("file").getAsString().split(":", 2)[1];
      BufferedImage image = images.get(file);
      if (image == null) {
        try (InputStream in = zip.getInputStream(zip.getEntry(file))) {
          image = ImageIO.read(in);
        }
        check(image != null, "real canvas PNG");
        images.put(file, image);
      }
      JsonArray rows = p.getAsJsonArray("chars");
      int columns = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length()),
          cw = image.getWidth() / columns,
          ch = image.getHeight() / rows.size(),
          height = p.get("height").getAsInt(),
          ascent = p.get("ascent").getAsInt();
      check(ascent <= height, "legal bitmap ascent");
      if (!p.get("file").getAsString().endsWith("hud-space.png"))
        check(cw <= 256 && ch <= 256, "glyph fits the client font texture");
      for (int y = 0; y < rows.size(); y++) {
        int[] codes = rows.get(y).getAsString().codePoints().toArray();
        for (int x = 0; x < codes.length; x++) {
          int code = codes[x];
          if (code == 0) continue;
          check(code >= NativeHudLayout.FIRST, "ordinary text and digits untouched");
          int ink = 0;
          outer:
          for (int col = cw - 1; col >= 0; col--)
            for (int row = 0; row < ch; row++)
              if ((image.getRGB(x * cw + col, y * ch + row) >>> 24) != 0) {
                ink = col + 1;
                break outer;
              }
          int width = (int) (.5f + ink * (float) height / ch) + 1;
          result.widths.put(code, width);
          result.ys.put(code, 7 - ascent);
        }
      }
    }
    return result;
  }

  private static int width(String value, Metrics m) {
    int total = 0;
    boolean bold = false;
    for (int n = 0; n < value.length(); ) {
      int c = value.codePointAt(n);
      n += Character.charCount(c);
      if (c == '§' && n < value.length()) {
        char f = Character.toLowerCase(value.charAt(n++));
        if (f == 'r' || "0123456789abcdef".indexOf(f) >= 0) bold = false;
        if (f == 'l') bold = true;
        continue;
      }
      Integer w = m.widths.get(c);
      check(w != null, "payload uses generated glyphs");
      total += w + (bold ? 1 : 0);
    }
    return total;
  }

  private static void pack(Path project, String v) throws Exception {
    Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "canvas-");
    PluginSettings settings = settings(project, root, v);
    DisplayDesign d = design();
    PackBuilder builder =
        new PackBuilder(
            settings, new MediaStore(settings), settings.minecraft, "http://127.0.0.1:8999");
    builder.designs(() -> d);
    PackRevision r = builder.build(GraphicSnapshot.empty());
    check(r.glyphs == 0, "canvas does not need a graphical prefix");
    if (!settings.minecraft.bitmapFonts()) {
      check(!r.hud.present(), "1.12 keeps explicit text fallback");
      try (ZipFile z = new ZipFile(r.file.toFile())) {
        check(z.getEntry("tabprefix-hud.json") == null, "no unsupported bitmap fonts on 1.12");
      }
      return;
    }
    check(r.hud.present() && r.hud.matches(d), "native layout published " + v);
    NativeHudLayout.Profile profile = r.hud.get("screen:free", -70, 8, "");
    check(
        profile != null && profile.frames() == NativeHudLayout.ALPHABET.length(),
        "complete text alphabet");
    check(r.hud.get("screen:free", -69, 8, "") == null, "stale Y rejected");
    check(r.hud.get("screen:free", -70, 12, "") == null, "stale size rejected");
    try {
      r.hud.profiles.clear();
      throw new AssertionError("Mutable layout");
    } catch (UnsupportedOperationException e) {
      checks++;
    }
    try (ZipFile z = new ZipFile(r.file.toFile())) {
      Metrics m = readMetrics(z);
      for (NativeHudLayout.Profile p : r.hud.profiles.values())
        for (int n = 0; n < p.frames(); n++) {
          check(m.widths.get(p.base + n) == p.advance(n), "manifest matches PNG advance");
          check(m.ys.get(p.base + n) == p.y, "exact Y-bearing");
        }
      for (int amount : new int[] {-8192, -512, -1, 0, 1, 512, 8192})
        check(width(NativeHudLayout.shift(amount), m) == amount, "signed spacer " + amount);
      NativeHudLayout.Encoded e = NativeHudLayout.encode(profile, "§aПривет §lWorld 123§r!");
      check(width(e.text, m) == e.width, "formatted text width");
      check(width(NativeHudLayout.position(e, -30), m) == 0, "block returns cursor to origin");
      NativeHudLayout.Encoded rgb = NativeHudLayout.encode(profile, "§x§1§2§3§4§5§6A B");
      check(rgb.text.contains("§r§x§1§2§3§4§5§6"), "RGB state restored after custom space");
      check(width(rgb.text, m) == rgb.width, "RGB text width");
      JsonArray prefix = json(z, "assets/tabprefix/font/prefix.json").getAsJsonArray("providers");
      check(prefix.size() == 0, "canvas fonts not duplicated in prefix-only font");
      NativeHudLayout restored = NativeHudLayout.read(json(z, "tabprefix-hud.json"));
      check(restored.matches(d), "manifest round trip");
    }
    check(builder.load(GraphicSnapshot.empty()).hud.matches(d), "restart reloads canvas");
    check(builder.build(GraphicSnapshot.empty()).hash.equals(r.hash), "deterministic canvas ZIP");
  }

  private static void extremes(Path project) throws Exception {
    Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "canvas-extreme-");
    PluginSettings s = settings(project, root, "1.13.2");
    JsonObject j = design().json();
    j.addProperty("sidebarY", -512);
    JsonObject item = j.getAsJsonArray("screen").get(0).getAsJsonObject();
    item.addProperty("y", -512);
    item.addProperty("height", 32);
    DisplayDesign d = new DisplayDesign(j);
    PackBuilder b = new PackBuilder(s, new MediaStore(s), s.minecraft, "http://127.0.0.1:8999");
    b.designs(() -> d);
    PackRevision r = b.build(GraphicSnapshot.empty());
    try (ZipFile z = new ZipFile(r.file.toFile())) {
      Metrics m = readMetrics(z);
      NativeHudLayout.Profile p = r.hud.get("screen:free", -512, 32, "");
      check(p != null, "extreme canvas geometry published");
      check(m.ys.get(p.base + 32) == -512, "extreme Y preserved");
      for (int n = 0; n < p.frames(); n++)
        check(m.widths.get(p.base + n) == p.advance(n), "scaled extreme advance matches PNG");
      NativeHudLayout.Encoded e = NativeHudLayout.encode(p, "AB 123");
      check(width(NativeHudLayout.position(e, 512), m) == 0, "extreme block maintains origin");
    }
  }

  private static void images(Path project) throws Exception {
    Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "canvas-image-");
    PluginSettings s = settings(project, root, "1.21.11");
    UUID id = UUID.randomUUID();
    int w = s.features.cellWidth, h = s.features.cellHeight;
    Path path = s.dataDirectory.resolve("assets").resolve(id.toString());
    Files.createDirectories(path);
    for (int f = 0; f < 2; f++) {
      BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) image.setRGB(x, y, f == 0 ? 0xff65c8ad : 0xff827bfa);
      ImageIO.write(image, "png", path.resolve(String.format("frame-%04d.png", f)).toFile());
    }
    AssetDescriptor asset =
        new AssetDescriptor(
            id, "gif", w, h, h, h - 1, new int[] {2, 3}, new int[] {0xe000, 0xe001}, 1);
    GraphicSnapshot snapshot =
        new GraphicSnapshot(
            Collections.singletonMap(id, asset), Collections.emptyMap(), Collections.emptyMap());
    JsonObject j = design().json(), item = j.getAsJsonArray("screen").get(0).getAsJsonObject();
    item.addProperty("kind", "IMAGE");
    item.addProperty("image", id.toString());
    item.addProperty("height", 16);
    DisplayDesign d = new DisplayDesign(j);
    PackBuilder b = new PackBuilder(s, new MediaStore(s), s.minecraft, "http://127.0.0.1:8999");
    b.designs(() -> d);
    PackRevision r = b.build(snapshot);
    NativeHudLayout.Profile p = r.hud.get("screen:free", -70, 16, id.toString());
    check(p != null && p.frames() == 2, "every GIF frame in native profile");
    check(p.advance(0) == (int) Math.round(w * 16.0 / h) + 1, "scaled image measured");
    try (ZipFile z = new ZipFile(r.file.toFile())) {
      Metrics m = readMetrics(z);
      for (int f = 0; f < 2; f++)
        check(width(p.glyph(f), m) == p.advance(f), "image frame advance");
      BufferedImage a, c;
      try (InputStream in =
          z.getInputStream(z.getEntry("assets/tabprefix/textures/font/hud-image-0-0.png"))) {
        a = ImageIO.read(in);
      }
      try (InputStream in =
          z.getInputStream(z.getEntry("assets/tabprefix/textures/font/hud-image-0-1.png"))) {
        c = ImageIO.read(in);
      }
      check(
          a.getRGB(0, 0) == 0xff65c8ad && c.getRGB(0, 0) == 0xff827bfa,
          "actual image colors preserved");
    }
    check(
        b.load(snapshot).hud.get("screen:free", -70, 16, id.toString()) != null,
        "image profile survives restart");
  }

  private static void standalone(Path project) throws Exception {
    try {
      Class.forName("net.luckperms.api.LuckPerms");
      throw new AssertionError("Standalone test still contains LuckPerms");
    } catch (ClassNotFoundException e) {
      checks++;
    }
    Class.forName("me.snowsun.tabprefix.TabPrefix");
    Class.forName("me.snowsun.tabprefix.bootstrap.PluginRuntime");
    LuckPermsBridge bridge = new LuckPermsBridge(null, null);
    bridge.localGroups(() -> Arrays.asList("admin", "VIP", "../bad", "default"));
    UUID id = UUID.randomUUID();
    Player player =
        (Player)
            Proxy.newProxyInstance(
                NativeCanvasIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (o, m, a) -> {
                  if (m.getName().equals("getUniqueId")) return id;
                  if (m.getName().equals("hasPermission"))
                    return a[0].equals("tabprefix.group.vip");
                  return null;
                });
    check(!bridge.integrated(), "standalone facade");
    check(
        bridge.identity(player, true).group.equals("vip"),
        "native permission chooses configured group");
    check(
        bridge.groups().equals(Arrays.asList("admin", "vip", "default")),
        "local group order and validation");
    check(!bridge.groupExistsAsync("../bad").get(), "unsafe group rejected");
    check(bridge.groupMetadata().size() == 3, "native group metadata");
    bridge.listen(true, u -> {}, () -> {});
    bridge.close();
    YamlConfiguration yaml =
        YamlConfiguration.loadConfiguration(project.resolve("resources/config.yml").toFile());
    yaml.set("luckperms.enabled", false);
    PluginSettings s =
        new PluginSettings(
            yaml, Files.createTempDirectory(project.resolve(".build/test-data"), "standalone-"));
    check(!s.luckPermsEnabled && s.prefixEnabled, "prefixes allowed without optional integration");
    System.out.println(
        "PASS: " + checks + " standalone checks with LuckPerms API absent from the classpath.");
  }
}
