package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.*;
import me.snowsun.tabprefix.infrastructure.persistence.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.presentation.display.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

public final class DreamIntegrationTest {
  private static int checks;

  private static void check(boolean v, String msg) {
    if (!v) throw new AssertionError(msg);
    checks++;
  }

  private static <T> T await(CompletableFuture<T> f) throws Exception {
    return f.get(15, TimeUnit.SECONDS);
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]),
        root = Files.createTempDirectory(project.resolve(".build"), "dream-");
    document();
    sidebar();
    hud();
    fonts(project, root);
    System.out.println(
        "PASS: "
            + checks
            + " Dream checks: wrappers, blank numbers, HUD lifecycle, SQLite glyph persistence and"
            + " legacy ZIP pixels.");
  }

  private static JsonObject item(String id, String kind) {
    JsonObject j = new JsonObject();
    j.addProperty("id", id);
    j.addProperty("anchor", "TITLE");
    j.addProperty("kind", kind);
    if (kind.equals("IMAGE")) j.addProperty("image", UUID.randomUUID().toString());
    JsonObject line = new JsonObject();
    line.addProperty("text", "<green>Hello {player}</green>");
    j.add("line", line);
    return j;
  }

  private static void document() {
    JsonObject j = DisplayDesign.defaults(false).json();
    JsonArray a = new JsonArray();
    a.add(item("a", "TEXT"));
    a.add(item("b", "IMAGE"));
    j.add("screen", a);
    j.addProperty("screenEnabled", true);
    DisplayDesign d = new DisplayDesign(j);
    check(d.screenEnabled && d.screen.size() == 2, "screen parsed");
    check(
        new DisplayDesign(d.json()).screen.get(1).image.equals(d.screen.get(1).image),
        "screen image round trip");
    check(new PlayerDisplaySettings(new JsonObject()).screen, "old preference defaults on");
    JsonObject pref = new JsonObject();
    pref.addProperty("screen", false);
    check(!new PlayerDisplaySettings(pref).screen, "screen opt-out");
    try {
      d.screen.clear();
      throw new AssertionError();
    } catch (UnsupportedOperationException e) {
      checks++;
    }
    JsonObject bad = item("bad", "TEXT");
    bad.addProperty("anchor", "UNSUPPORTED");
    a.add(bad);
    try {
      new DisplayDesign(j);
      throw new AssertionError();
    } catch (IllegalArgumentException e) {
      checks++;
    }
    JsonObject slot = new JsonObject();
    slot.addProperty("kind", "IMAGE");
    slot.addProperty("image", UUID.randomUUID().toString());
    check(new DisplayDesign.Slot(slot).json().has("image"), "TAB image ID retained");
  }

  public interface NativeObjective extends Objective {
    NativeHandle getHandle();
  }

  public static final class NativeHandle {
    final String name;
    String title = "";
    final Map<String, Integer> scores = new HashMap<>();
    int writes, numbers;
    BlankFormat format;

    NativeHandle(String n) {
      name = n;
    }

    public void setNumberFormat(BlankFormat value) {
      format = value;
      numbers++;
    }
  }

  private static final class Board {
    NativeHandle handle;
    boolean active, published;
    int registrations, removals, teamCount;
    final Map<String, Team> teams = new HashMap<>();
    Scoreboard api;

    Board() {
      api =
          (Scoreboard)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {Scoreboard.class},
                  (p, m, a) -> {
                    switch (m.getName()) {
                      case "getObjective":
                        return handle == null || a[0] instanceof DisplaySlot && !active
                            ? null
                            : wrapper(handle);
                      case "registerNewObjective":
                        handle = new NativeHandle((String) a[0]);
                        registrations++;
                        return wrapper(handle);
                      case "getTeam":
                        return teams.get(a[0]);
                      case "registerNewTeam":
                        return team((String) a[0]);
                      case "resetScores":
                        handle.scores.remove(a[0]);
                        return null;
                      case "equals":
                        return p == a[0];
                      case "hashCode":
                        return System.identityHashCode(p);
                      default:
                        return zero(m.getReturnType());
                    }
                  });
    }

    Objective wrapper(NativeHandle h) {
      return (Objective)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {NativeObjective.class},
              (p, m, a) -> {
                switch (m.getName()) {
                  case "getHandle":
                    return h;
                  case "getName":
                    return h.name;
                  case "getScoreboard":
                    return api;
                  case "getDisplayName":
                    return h.title;
                  case "setDisplayName":
                    h.title = (String) a[0];
                    return null;
                  case "setDisplaySlot":
                    active = true;
                    published = !h.scores.isEmpty();
                    return null;
                  case "unregister":
                    removals++;
                    if (handle == h) {
                      handle = null;
                      active = false;
                    }
                    return null;
                  case "getScore":
                    String entry = (String) a[0];
                    return Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Score.class},
                        (sp, sm, sa) -> {
                          if (sm.getName().equals("getScore"))
                            return h.scores.getOrDefault(entry, 0);
                          if (sm.getName().equals("setScore")) {
                            h.writes++;
                            h.scores.put(entry, (Integer) sa[0]);
                          }
                          return zero(sm.getReturnType());
                        });
                  default:
                    return zero(m.getReturnType());
                }
              });
    }

    Team team(String name) {
      teamCount++;
      Map<String, String> text = new HashMap<>();
      Team t =
          (Team)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {Team.class},
                  (p, m, a) -> {
                    if (m.getName().startsWith("get"))
                      return text.getOrDefault(m.getName().substring(3), "");
                    if (m.getName().startsWith("set")) {
                      text.put(m.getName().substring(3), (String) a[0]);
                      return null;
                    }
                    if (m.getName().equals("unregister")) {
                      teams.remove(name);
                      return null;
                    }
                    return zero(m.getReturnType());
                  });
      teams.put(name, t);
      return t;
    }
  }

  private static void sidebar() {
    Board b = new Board();
    SidebarView view = new SidebarView(MinecraftVersion.parse("1.21.11"));
    check(view.render(b.api, "Title", Arrays.asList("a", "b")), "initial sidebar");
    check(b.published, "lines built before display slot");
    check(b.handle.format == BlankFormat.INSTANCE && b.handle.numbers == 1, "native blank once");
    int writes = b.handle.writes;
    for (int i = 0; i < 50; i++)
      check(view.render(b.api, "Title", Arrays.asList("a", "b")), "fresh wrapper refresh " + i);
    check(
        b.registrations == 1 && b.removals == 0 && b.teamCount == 2,
        "wrappers never recreate board content");
    check(b.handle.writes == writes, "unchanged scores are differential");
    view.render(b.api, "Changed", Arrays.asList("c", "b", "d"));
    check(b.registrations == 1 && b.handle.scores.size() == 3, "custom layout changes in place");
    NativeHandle foreign = new NativeHandle("tp_sidebar");
    b.handle = foreign;
    b.active = true;
    check(!view.render(b.api, "Ours", Arrays.asList("x")), "foreign same-name objective respected");
    check(b.handle == foreign && b.removals == 0, "foreign objective never unregistered");
    view.close();
  }

  private static void hud() {
    List<Object[]> titles = new ArrayList<>();
    List<String> actions = new ArrayList<>();
    int[] resets = {0};
    UUID id = UUID.randomUUID();
    Player.Spigot spigot =
        new Player.Spigot() {
          @Override
          public void sendMessage(ChatMessageType type, BaseComponent... parts) {
            check(type == ChatMessageType.ACTION_BAR, "native action-bar type");
            actions.add(BaseComponent.toPlainText(parts));
          }
        };
    Player p =
        (Player)
            Proxy.newProxyInstance(
                DreamIntegrationTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (o, m, a) -> {
                  switch (m.getName()) {
                    case "getUniqueId":
                      return id;
                    case "isOnline":
                      return true;
                    case "spigot":
                      return spigot;
                    case "sendTitle":
                      titles.add(a);
                      return null;
                    case "resetTitle":
                      resets[0]++;
                      return null;
                    default:
                      return zero(m.getReturnType());
                  }
                });
    ScreenHudView v = new ScreenHudView();
    v.render(p, "Title", "Sub", "Action", 1);
    v.render(p, "Title", "Sub", "Action", 2);
    check(titles.size() == 1 && actions.size() == 1, "unchanged HUD not sent twice");
    check(
        titles.get(0)[2].equals(0) && titles.get(0)[4].equals(0),
        "persistent title has no fade flash");
    v.render(p, "Title", "Sub", "Action", 41);
    check(titles.size() == 2 && actions.size() == 2, "heartbeat renews client lifetime");
    v.render(p, "", "", "", 42);
    check(resets[0] == 1 && actions.get(actions.size() - 1).isEmpty(), "disable clears own HUD");
    v.close();
    check(resets[0] == 1, "shutdown does not clear twice");
  }

  private static void fonts(Path project, Path root) throws Exception {
    PluginSettings s =
        new PluginSettings(
            YamlConfiguration.loadConfiguration(project.resolve("resources/config.yml").toFile()),
            root,
            MinecraftVersion.parse("1.12.2"));
    byte[] original = new byte[65536];
    original[65] = 0x57;
    original[0x410] = 0x24;
    original[0xe0a0] = 0x0f;
    LegacyFontAssets fonts = new LegacyFontAssets(s.dataDirectory.resolve("legacy-font"));
    fonts.importWidths(original);
    BufferedImage old = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
    old.setRGB(0, 160, 0xff0077ff);
    ImageIO.write(old, "png", s.dataDirectory.resolve("legacy-font/unicode_page_e0.png").toFile());
    ByteArrayOutputStream client = new ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(client)) {
      z.putNextEntry(new java.util.zip.ZipEntry("assets/minecraft/font/glyph_sizes.bin"));
      z.write(original);
      z.closeEntry();
      z.putNextEntry(
          new java.util.zip.ZipEntry("assets/minecraft/textures/font/unicode_page_04.png"));
      z.write(new byte[] {1, 2, 3});
      z.closeEntry();
      z.putNextEntry(new java.util.zip.ZipEntry("bad/../../outside"));
      z.write(9);
      z.closeEntry();
    }
    LegacyFontAssets extract = new LegacyFontAssets(root.resolve("extracted"));
    extract.extractFonts(client.toByteArray());
    check(Arrays.equals(original, extract.widths()), "metrics extracted from client JAR");
    check(Arrays.equals(extract.page(4), new byte[] {1, 2, 3}), "Unicode page extraction");
    check(!Files.exists(root.resolve("outside")), "unrelated paths ignored");
    try {
      extract.extractFonts(new byte[20]);
      throw new AssertionError();
    } catch (IOException e) {
      checks++;
    }
    try (SqliteDatabase db = new SqliteDatabase(s.databaseFile, Logger.getLogger("test"))) {
      await(db.initialize());
      PrefixService text = new PrefixService(new SqliteGroupPrefixRepository(db));
      await(text.initialize());
      try {
        GraphicService graphics =
            new GraphicService(new SqliteGraphicRepository(db), text, s.features);
        await(graphics.initialize());
        MediaStore media = new MediaStore(s);
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 32; x++)
          for (int y = 0; y < 16; y++) image.setRGB(x, y, x < 16 ? 0xffff3344 : 0xff44ee55);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        EditorOptions options = EditorOptions.defaults(16, 13);
        ProcessedMedia processed =
            new ImageDecoder().decode(png.toByteArray(), options, s.features);
        AssetDescriptor a =
            await(graphics.registerScreen(media.store(png.toByteArray(), processed, options)));
        check(a.assigned() && a.glyphCode(0) == 0xe000, "standalone image gets permanent code");
        check(
            graphics.snapshot().prefixes.isEmpty() && text.count() == 0,
            "upload changes no group prefix");
        check(
            await(graphics.registerScreen(media.store(png.toByteArray(), processed, options)))
                    .id
                    .equals(a.id)
                && graphics.snapshot().glyphs() == 1,
            "dedupe retains same glyph");
        PackBuilder builder = new PackBuilder(s, media, s.minecraft, "http://localhost");
        PackRevision pack = builder.build(graphics.snapshot());
        check(
            pack.packFormat == 3 && pack.glyphs == 1 && pack.assets.contains(a.id),
            "legacy pack includes real glyph");
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(pack.file.toFile())) {
          check(
              z.getEntry("assets/minecraft/font/default.json") == null,
              "no modern font JSON on legacy");
          byte[] widths = new byte[65536];
          try (DataInputStream in =
              new DataInputStream(
                  z.getInputStream(z.getEntry("assets/minecraft/font/glyph_sizes.bin")))) {
            in.readFully(widths);
          }
          check(
              widths[65] == original[65] && widths[0x410] == original[0x410],
              "Latin/Cyrillic widths unchanged");
          check(widths[a.glyphCode(0)] == 0x0f, "custom glyph enabled");
          BufferedImage atlas =
              ImageIO.read(
                  z.getInputStream(
                      z.getEntry("assets/minecraft/textures/font/unicode_page_e0.png")));
          check(atlas.getWidth() == 256 && atlas.getHeight() == 256, "exact Unicode geometry");
          check(atlas.getRGB(0, 160) == old.getRGB(0, 160), "unassigned page pixels preserved");
          check(
              atlas.getRGB(1, 6) == 0xffff3344 && atlas.getRGB(12, 6) == 0xff44ee55,
              "assigned glyph pixels resampled");
        }
        check(builder.load(graphics.snapshot()).assets.contains(a.id), "pack reload retains glyph");
        check(
            builder.build(graphics.snapshot()).hash.equals(pack.hash), "deterministic legacy pack");
        GraphicService reopened =
            new GraphicService(new SqliteGraphicRepository(db), text, s.features);
        await(reopened.initialize());
        check(
            reopened.snapshot().assets.get(a.id).glyphCode(0) == 0xe000,
            "SQLite restart retains code");
      } finally {
        text.close();
      }
    }
  }

  private static Object zero(Class<?> t) {
    if (t == boolean.class) return false;
    if (t == int.class) return 0;
    if (t == long.class) return 0L;
    return null;
  }
}
