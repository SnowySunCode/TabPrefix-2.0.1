package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.bukkit.*;
import me.snowsun.tabprefix.infrastructure.media.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.PackBuilder;
import me.snowsun.tabprefix.presentation.display.*;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import me.snowsun.tabprefix.util.AtomicFiles;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

/** Version rules and real ZIPs, plus independently loaded packet contract fixtures. */
public final class CompatibilityIntegrationTest {
  private static int checks;
  private static final Logger LOG = Logger.getLogger("TabPrefix-Compatibility");

  private static void check(boolean ok, String name) {
    if (!ok) throw new AssertionError(name);
    checks++;
  }

  private static YamlConfiguration yaml(Path project) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    try (Reader r =
        Files.newBufferedReader(project.resolve("resources/config.yml"), StandardCharsets.UTF_8)) {
      yaml.load(r);
    }
    yaml.set("glyphs.width", 4);
    yaml.set("glyphs.height", 2);
    yaml.set("glyphs.render-height", 2);
    yaml.set("glyphs.ascent", 1);
    yaml.set("atlas.width", 8);
    yaml.set("atlas.height", 4);
    return yaml;
  }

  public static void main(String[] args) throws Exception {
    LOG.setUseParentHandlers(false);
    Path project = Paths.get(args[0]);
    if (args.length > 1 && args[1].equals("--packets")) packets(args[2]);
    else {
      Path root = Files.createTempDirectory(project.resolve(".build/test-data"), "compat-");
      try {
        versions(project, root);
        packs(project, root);
        oldBoards();
      } finally {
        AtomicFiles.deleteTree(root);
      }
    }
    System.out.println(
        "PASS: "
            + checks
            + " compatibility checks"
            + (args.length > 2
                ? " (" + args[2] + ")"
                : " (versions, colors, scoreboards, resource-pack ZIPs and migration)"));
  }

  private static void versions(Path project, Path root) throws Exception {
    for (String v :
        new String[] {
          "1.12", "1.12.2", "1.13.2", "1.14.4", "1.15.2", "1.16.5", "1.17.1", "1.18.2", "1.19.4",
          "1.20.6", "1.21.11", "26.1.2", "26.2", "26.3"
        }) check(MinecraftVersion.parse(v + "-R0.1-SNAPSHOT").supported(), "supported " + v);
    for (String v :
        new String[] {
          "1.11.2", "1.12.3", "1.16.6", "1.20.7", "1.21.12", "1.22", "1.26", "26.0", "26.4", "27.1"
        }) check(!MinecraftVersion.parse(v).supported(), "unknown release " + v);
    check(
        MinecraftVersion.parse("26.1").compareTo(MinecraftVersion.parse("1.21.11")) > 0,
        "year numbering sorts after 1.x");
    check(
        MinecraftVersion.parse("26.1.2.build.67-stable").toString().equals("26.1.2"),
        "Paper build-qualified version with patch");
    check(
        MinecraftVersion.parse("26.3.build.157-beta").toString().equals("26.3"),
        "Paper build-qualified version without patch");
    check(
        MinecraftVersion.parse("26.2.build.2627-stable").supported(),
        "Purpur build-qualified version");
    YamlConfiguration y = yaml(project);
    y.set("compatibility.minecraft.min-version", "1.16");
    y.set("compatibility.minecraft.max-version", "1.16.5");
    PluginSettings migrated = new PluginSettings(y, root);
    check(
        migrated.minVersion.toString().equals("1.12")
            && migrated.maxVersion.toString().equals("26.3.99"),
        "old exact factory range migrates");
    y.set("compatibility.minecraft.min-version", "1.13");
    y.set("compatibility.minecraft.max-version", "1.20.4");
    PluginSettings custom = new PluginSettings(y, root);
    check(
        custom.minVersion.toString().equals("1.13")
            && custom.maxVersion.toString().equals("1.20.4"),
        "custom restrictions preserved");
    for (String v :
        new String[] {"1.12.2", "1.13.2", "1.14.4", "1.15.2", "1.16.5", "1.21.11", "26.3"}) {
      MinecraftVersion version = MinecraftVersion.parse(v);
      TextRenderer renderer = new TextRenderer(new PluginSettings(y, root, version));
      String rgb = renderer.prefix("<#34e1a4>Color</#34e1a4>", TextFormat.MINIMESSAGE);
      check(rgb.contains("§x") == version.rgb(), "RGB policy " + v);
      check(
          renderer.prefix("&#34e1a4Color", TextFormat.LEGACY).contains("§x") == version.rgb(),
          "legacy RGB policy " + v);
      check(
          renderer
                  .legacy(
                      renderer.display(
                          "<gradient:#ff1100:#0033ff>{player}</gradient>",
                          Collections.singletonMap("player", "Test")))
                  .contains("§x")
              == version.rgb(),
          "display gradient policy " + v);
    }
    check(
        MinecraftVersion.parse("1.12.2").teamTextLimit() == 16
            && MinecraftVersion.parse("1.12.2").objectiveTitleLimit() == 32,
        "legacy scoreboard limits");
    check(
        MinecraftVersion.parse("1.13").teamTextLimit() == 64
            && MinecraftVersion.parse("26.3").objectiveTitleLimit() == 128,
        "modern scoreboard limits");
  }

  private static void packs(Path project, Path root) throws Exception {
    String[][] cases = {
      {"1.12.2", "3"},
      {"1.13.2", "4"},
      {"1.14.4", "4"},
      {"1.15.2", "5"},
      {"1.16", "5"},
      {"1.16.1", "5"},
      {"1.16.2", "6"},
      {"1.16.5", "6"},
      {"1.17.1", "7"},
      {"1.18.2", "8"},
      {"1.19", "9"},
      {"1.19.2", "9"},
      {"1.19.3", "12"},
      {"1.19.4", "13"},
      {"1.20", "15"},
      {"1.20.1", "15"},
      {"1.20.2", "18"},
      {"1.20.3", "22"},
      {"1.20.4", "22"},
      {"1.20.5", "32"},
      {"1.20.6", "32"},
      {"1.21", "34"},
      {"1.21.1", "34"},
      {"1.21.2", "42"},
      {"1.21.3", "42"},
      {"1.21.4", "46"},
      {"1.21.5", "55"},
      {"1.21.6", "63"},
      {"1.21.7", "64"},
      {"1.21.8", "64"},
      {"1.21.9", "69.0"},
      {"1.21.10", "69.0"},
      {"1.21.11", "75.0"},
      {"26.1", "84.0"},
      {"26.1.2", "84.0"},
      {"26.2", "88.0"},
      {"26.3", "97.1"}
    };
    for (String[] c : cases) {
      Path path = Files.createDirectory(root.resolve("pack-" + c[0]));
      MinecraftVersion version = MinecraftVersion.parse(c[0]);
      PluginSettings s = new PluginSettings(yaml(project), path, version);
      MediaStore media = new MediaStore(s);
      PackBuilder builder = new PackBuilder(s, media, version, "http://127.0.0.1");
      PackRevision pack = builder.build(GraphicSnapshot.empty());
      check(
          ResourcePackFormat.forVersion(version).toString().equals(c[1]),
          "resource format " + c[0]);
      try (ZipFile zip = new ZipFile(pack.file.toFile())) {
        JsonObject meta =
            new JsonParser()
                .parse(
                    new InputStreamReader(
                        zip.getInputStream(zip.getEntry("pack.mcmeta")), StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("pack");
        check(ResourcePackFormat.forVersion(version).matches(meta), "actual ZIP metadata " + c[0]);
        check(
            meta.has("min_format") == ResourcePackFormat.forVersion(version).rangeMetadata,
            "metadata generation " + c[0]);
        check(
            (zip.getEntry("assets/minecraft/font/default.json") != null) == version.bitmapFonts(),
            "font capability " + c[0]);
      }
      check(builder.load(GraphicSnapshot.empty()).hash.equals(pack.hash), "ZIP reload " + c[0]);
      check(
          builder.build(GraphicSnapshot.empty()).hash.equals(pack.hash),
          "deterministic ZIP " + c[0]);
    }
    Path migration = Files.createDirectory(root.resolve("migration"));
    PluginSettings s = new PluginSettings(yaml(project), migration);
    MediaStore media = new MediaStore(s);
    BufferedImage png = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
    png.setRGB(0, 0, 0xff00ff00);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(png, "png", bytes);
    EditorOptions options = EditorOptions.defaults(2, 1);
    AssetDescriptor stored =
        media.store(
            bytes.toByteArray(),
            new ImageDecoder().decode(bytes.toByteArray(), options, s.features),
            options);
    AssetDescriptor assigned =
        new AssetDescriptor(
            stored.id,
            stored.sourceFormat,
            stored.width,
            stored.height,
            stored.renderHeight,
            stored.ascent,
            stored.delays(),
            new int[] {0xe000},
            stored.createdAt);
    GraphicSnapshot snapshot =
        new GraphicSnapshot(
            Collections.singletonMap(assigned.id, assigned),
            Collections.emptyMap(),
            Collections.emptyMap());
    PackRevision original =
        new PackBuilder(s, media, MinecraftVersion.parse("1.16.5"), "http://localhost")
            .build(snapshot);
    PackBuilder next =
        new PackBuilder(s, media, MinecraftVersion.parse("26.3"), "http://localhost");
    PackRevision upgraded = next.load(snapshot);
    check(
        !upgraded.hash.equals(original.hash) && upgraded.packFormat == 97,
        "server upgrade replaces pack metadata");
    check(
        upgraded.assets.contains(assigned.id)
            && upgraded.glyphs == 1
            && assigned.glyphCode(0) == 0xe000,
        "upgrade preserves image and permanent glyph");
    check(
        next.load(snapshot).hash.equals(upgraded.hash), "upgraded pack reopens without rebuilding");
    new me.snowsun.tabprefix.infrastructure.resourcepack.LegacyFontAssets(
            s.dataDirectory.resolve("legacy-font"))
        .importWidths(new byte[65536]);
    PackRevision legacy =
        new PackBuilder(s, media, MinecraftVersion.parse("1.12.2"), "http://localhost")
            .load(snapshot);
    check(
        legacy.packFormat == 3 && legacy.glyphs == 1 && legacy.assets.contains(assigned.id),
        "1.12 includes generated Unicode glyph");
    check(
        assigned.assigned() && media.frame(assigned, 0) != null,
        "1.12 fallback retains original assets");
    PackRevision restored = next.load(snapshot);
    check(
        restored.hash.equals(upgraded.hash) && restored.glyphs == 1,
        "later upgrade restores identical image pack");
    try {
      next.rollback(legacy.hash, snapshot);
      throw new AssertionError("foreign format rollback allowed");
    } catch (IOException expected) {
      checks++;
    }
  }

  private static void oldBoards() {
    final Map<String, String> text = new HashMap<>();
    final Set<String> entries = new HashSet<>();
    Team team =
        (Team)
            Proxy.newProxyInstance(
                CompatibilityIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Team.class},
                (p, m, a) -> {
                  String n = m.getName();
                  if (n.equals("getName")) return "tpsb0";
                  if (n.equals("getPrefix") || n.equals("getSuffix"))
                    return text.getOrDefault(n.substring(3), "");
                  if (n.equals("setPrefix") || n.equals("setSuffix")) {
                    check(((String) a[0]).length() <= 16, "1.12 team limit enforced");
                    text.put(n.substring(3), (String) a[0]);
                    return null;
                  }
                  if (n.equals("getOption")) return Team.OptionStatus.ALWAYS;
                  if (n.equals("hasEntry")) return entries.contains(a[0]);
                  if (n.equals("addEntry")) {
                    entries.add((String) a[0]);
                    return null;
                  }
                  return zero(m.getReturnType());
                });
    Score score =
        (Score)
            Proxy.newProxyInstance(
                CompatibilityIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Score.class},
                (p, m, a) -> zero(m.getReturnType()));
    Objective objective =
        (Objective)
            Proxy.newProxyInstance(
                CompatibilityIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Objective.class},
                (p, m, a) -> {
                  if (m.getName().equals("setDisplayName")) {
                    check(((String) a[0]).length() <= 32, "1.12 title limit enforced");
                    text.put("title", (String) a[0]);
                    return null;
                  }
                  if (m.getName().equals("getDisplayName")) return text.getOrDefault("title", "");
                  if (m.getName().equals("getScore")) return score;
                  return zero(m.getReturnType());
                });
    Scoreboard board =
        (Scoreboard)
            Proxy.newProxyInstance(
                CompatibilityIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Scoreboard.class},
                (p, m, a) -> {
                  if (m.getName().equals("registerNewObjective")) {
                    check(a.length == 2, "old objective overload used");
                    return objective;
                  }
                  if (m.getName().equals("registerNewTeam")) return team;
                  return zero(m.getReturnType());
                });
    SidebarView sidebar = new SidebarView(MinecraftVersion.parse("1.12.2"));
    check(
        sidebar.render(
            board,
            "A long title that exceeds the old thirty two character limit",
            Collections.singletonList("§6A very long colored scoreboard line")),
        "old sidebar renders");
    check(
        text.get("Prefix").length() <= 16 && text.get("Suffix").length() <= 16,
        "both old line segments fit");
    sidebar.close();
  }

  private static Object zero(Class<?> type) {
    if (!type.isPrimitive() || type == void.class) return null;
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == double.class) return 0d;
    if (type == float.class) return 0f;
    if (type == short.class) return (short) 0;
    if (type == byte.class) return (byte) 0;
    return '\0';
  }

  private static void packets(String family) throws Exception {
    Class<?> craft = Class.forName("compat.fixture.CraftPlayer"),
        entity = craft.getMethod("getHandle").getReturnType(),
        profile = Class.forName("com.mojang.authlib.GameProfile");
    UUID realId = UUID.randomUUID();
    Object realProfile;
    try {
      realProfile =
          profile.getConstructor(UUID.class, String.class).newInstance(realId, "ActualPlayer");
      Object props = profile.getMethod("getProperties").invoke(realProfile);
      props
          .getClass()
          .getMethod("put", Object.class, Object.class)
          .invoke(props, "textures", "signed-skin");
    } catch (NoSuchMethodException newer) {
      Constructor<?> c = profile.getConstructors()[0];
      Class<?> props = c.getParameterTypes()[2];
      realProfile =
          c.newInstance(
              realId,
              "ActualPlayer",
              props.getConstructor(String.class).newInstance("signed-skin"));
    }
    Object real = entity.getConstructor(profile).newInstance(realProfile);
    Object connection = field(real, "connection", "playerConnection");
    @SuppressWarnings("unchecked")
    List<Object> sent = (List<Object>) field(connection, "packets");
    Map<String, String> bukkit = new HashMap<>();
    Player player =
        (Player)
            Proxy.newProxyInstance(
                CompatibilityIntegrationTest.class.getClassLoader(),
                new Class<?>[] {craft},
                (p, m, a) -> {
                  switch (m.getName()) {
                    case "getHandle":
                      return real;
                    case "getUniqueId":
                      return realId;
                    case "getName":
                      return "ActualPlayer";
                    case "getPing":
                      return 42;
                    case "getPlayerListHeader":
                      return bukkit.get("header");
                    case "getPlayerListFooter":
                      return bukkit.get("footer");
                    case "setPlayerListHeader":
                      bukkit.put("header", (String) a[0]);
                      return null;
                    case "setPlayerListFooter":
                      bukkit.put("footer", (String) a[0]);
                      return null;
                    case "equals":
                      return p == a[0];
                    case "hashCode":
                      return System.identityHashCode(p);
                    default:
                      return zero(m.getReturnType());
                  }
                });
    ViewerTabPackets names = new ViewerTabPackets(LOG);
    check(
        names.send(player, Collections.singletonMap(player, "§aRenamed")),
        "native name adapter initializes");
    Object renamed = packetEntries(sent.get(0)).get(0);
    check(field(renamed, "profile", "b") == realProfile, "real profile identity preserved");
    check(
        field(renamed, "display", "displayName", "f").toString().contains("Renamed"),
        "native display component replaced");
    if (family.equals("spigot19"))
      check(
          field(renamed, "keyData").toString().equals("real-key"),
          "legacy public key data preserved");
    if (family.startsWith("update") || family.equals("mojang26")) {
      check(Boolean.FALSE.equals(field(renamed, "listed", "c")), "real listed state preserved");
      check(
          field(renamed, "gameMode", "e").toString().equals("SPECTATOR"),
          "real game mode preserved");
      check(
          field(renamed, "chatSession", family.equals("update8") ? "h" : "g", "i")
              .toString()
              .equals("real-session"),
          "signed chat session preserved");
      if (!family.equals("update7"))
        check(
            ((Number) field(renamed, "listOrder", family.equals("update8") ? "g" : "h")).intValue()
                == 600,
            "real list order preserved");
      if (family.equals("update9") || family.equals("mojang26"))
        check(Boolean.FALSE.equals(field(renamed, "showHat", "g")), "real hat flag preserved");
    }
    sent.clear();
    VirtualTabPackets layout = new VirtualTabPackets(LOG);
    List<VirtualTabPackets.Cell> cells = new ArrayList<>();
    for (int i = 0; i < 80; i++)
      cells.add(new VirtualTabPackets.Cell("Cell " + i, i == 0 ? player : null));
    check(
        layout.render(player, cells, Collections.singleton(player), 0), "fixed layout initializes");
    List<?> added = packetEntries(sent.get(0));
    check(added.size() == 80, "80 synthetic entries batched");
    Set<UUID> ids = new HashSet<>();
    for (Object e : added) {
      Object pr = field(e, "profile", "b");
      UUID id = (UUID) invoke(pr, "getId", "id");
      ids.add(id);
      check(!id.equals(realId), "synthetic UUID differs from real player");
    }
    check(ids.size() == 80, "stable slots have distinct UUIDs");
    Object bound = field(added.get(0), "profile", "b");
    check(
        invoke(bound, "getProperties", "properties").toString().contains("signed-skin"),
        "skin properties copied");
    if (family.equals("mojang26"))
      check(
          invoke(field(added.get(1), "profile", "b"), "properties").toString().equals("{}"),
          "immutable empty properties constructed");
    if (family.startsWith("update") || family.equals("mojang26")) {
      check(Boolean.TRUE.equals(field(added.get(0), "listed", "c")), "synthetic player listed");
      if (!family.equals("update7"))
        check(
            ((Number) field(added.get(0), "listOrder", family.equals("update8") ? "g" : "h"))
                    .intValue()
                == Integer.MAX_VALUE,
            "synthetic list order sorts first");
    }
    check(
        layout.render(player, cells, Collections.singleton(player), 1) && sent.size() == 1,
        "unchanged frame sends no packet");
    cells.set(1, new VirtualTabPackets.Cell("Updated", null));
    check(
        layout.render(player, cells, Collections.singleton(player), 2)
            && packetEntries(sent.get(1)).size() == 1,
        "differential name update");
    layout.restore(player, Collections.singleton(player));
    Object removed = sent.get(2);
    if (family.startsWith("update") || family.equals("mojang26")) {
      @SuppressWarnings("unchecked")
      List<UUID> removedIds = (List<UUID>) field(removed, "ids");
      check(
          removedIds.size() == 80 && !removedIds.contains(realId),
          "separate removal packet only removes slots");
    } else check(packetEntries(removed).size() == 80, "legacy removal packet only removes slots");
    layout.restore(player, Collections.singleton(player));
    check(sent.size() == 3, "repeat restore is idempotent");
    check(layout.ping(player) == 42, "latency available on this API");
    sent.clear();
    MinecraftVersion version =
        MinecraftVersion.parse(family.startsWith("legacy") ? "1.12.2" : "1.21.11");
    HeaderFooterAccess hf = new HeaderFooterAccess(LOG, version);
    hf.header(player, "§eTop");
    hf.footer(player, "Bottom");
    check(
        hf.header(player).equals("§eTop") && hf.footer(player).equals("Bottom"),
        "header and footer coexist");
    if (family.startsWith("legacy"))
      check(
          sent.size() == 2 && field(sent.get(1), "footer").toString().contains("Bottom"),
          "native legacy header/footer packet");
    hf.header(player, null);
    hf.footer(player, null);
    check(hf.header(player) == null && hf.footer(player) == null, "header/footer restore");
    sent.clear();
    fieldObject(connection, "fail").setBoolean(connection, true);
    check(
        !names.send(player, Collections.singletonMap(player, "Fail")),
        "name adapter safely falls back on send error");
    check(
        !layout.render(player, cells, Collections.singleton(player), 3) && !layout.available(),
        "layout fails safely on send error");
  }

  private static Object invoke(Object target, String... names) throws Exception {
    for (String n : names)
      try {
        return target.getClass().getMethod(n).invoke(target);
      } catch (NoSuchMethodException ignored) {
      }
    throw new NoSuchMethodException(Arrays.toString(names));
  }

  private static Field fieldObject(Object target, String name) throws Exception {
    for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException ignored) {
      }
    throw new NoSuchFieldException(name);
  }

  private static Object field(Object target, String... names) throws Exception {
    for (String n : names)
      try {
        return fieldObject(target, n).get(target);
      } catch (NoSuchFieldException ignored) {
      }
    throw new NoSuchFieldException(Arrays.toString(names));
  }

  private static List<?> packetEntries(Object packet) throws Exception {
    for (Field f : packet.getClass().getDeclaredFields())
      if (List.class.isAssignableFrom(f.getType())) {
        f.setAccessible(true);
        return (List<?>) f.get(packet);
      }
    throw new NoSuchFieldException("packet entries");
  }
}
