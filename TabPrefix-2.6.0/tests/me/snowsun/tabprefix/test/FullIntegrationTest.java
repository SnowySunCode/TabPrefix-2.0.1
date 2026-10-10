package me.snowsun.tabprefix.test;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.zip.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.audit.AuditService;
import me.snowsun.tabprefix.infrastructure.media.*;
import me.snowsun.tabprefix.infrastructure.persistence.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.*;
import me.snowsun.tabprefix.infrastructure.web.*;
import me.snowsun.tabprefix.presentation.display.*;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import me.snowsun.tabprefix.util.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Real image codecs, native SQLite, generated packs, HTTP and final shaded bytecode. */
public final class FullIntegrationTest {
  private static final Logger LOG = Logger.getLogger("TabPrefix-FullTest");
  private static final UUID OWNER = UUID.fromString("10000000-0000-0000-0000-000000000001"),
      OTHER = UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static int checks;

  private static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    checks++;
  }

  private static <T> T await(CompletableFuture<T> f) throws Exception {
    return f.get(15, TimeUnit.SECONDS);
  }

  private static YamlConfiguration yaml(Path project) throws Exception {
    YamlConfiguration y = new YamlConfiguration();
    try (Reader r =
        Files.newBufferedReader(project.resolve("resources/config.yml"), StandardCharsets.UTF_8)) {
      y.load(r);
    }
    return y;
  }

  private static final class Fixture implements AutoCloseable {
    final Path root;
    final YamlConfiguration yaml;
    final PluginSettings settings;
    final SqliteDatabase db;
    final PrefixService texts;
    final GraphicService graphics;
    final MediaStore media;
    final PackBuilder builder;
    final ResourcePackService packs;
    EditorServer editor;

    Fixture(Path project) throws Exception {
      root = Files.createTempDirectory(project.resolve(".build/test-data"), "full-");
      yaml = yaml(project);
      yaml.set("glyphs.width", 4);
      yaml.set("glyphs.height", 2);
      yaml.set("glyphs.render-height", 2);
      yaml.set("glyphs.ascent", 1);
      yaml.set("atlas.width", 8);
      yaml.set("atlas.height", 4);
      yaml.set("images.max-file-size-mb", 1);
      yaml.set("web.bind-address", "127.0.0.1");
      yaml.set("web.public-host", "127.0.0.1");
      try (ServerSocket socket = new ServerSocket(0)) {
        yaml.set("web.port", socket.getLocalPort());
      }
      settings = new PluginSettings(yaml, root);
      db = new SqliteDatabase(settings.databaseFile, LOG);
      await(db.initialize());
      texts = new PrefixService(new SqliteGroupPrefixRepository(db));
      await(texts.initialize());
      graphics = new GraphicService(new SqliteGraphicRepository(db), texts, settings.features);
      await(graphics.initialize());
      media = new MediaStore(settings);
      String base = PublicAddress.resolve(settings.features, "");
      builder = new PackBuilder(settings, media, MinecraftVersion.parse("1.16.5"), base);
      packs = new ResourcePackService(builder, graphics, (event, values) -> {});
      await(packs.initialize());
    }

    void http() throws Exception {
      editor =
          new EditorServer(
              settings,
              graphics,
              texts,
              media,
              packs,
              (event, values) -> {},
              PublicAddress.resolve(settings.features, ""),
              LOG);
      editor.start();
    }

    @Override
    public void close() throws Exception {
      if (editor != null) editor.close();
      packs.close();
      texts.close();
      db.close();
      AtomicFiles.deleteTree(root);
    }
  }

  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[0]);
    Files.createDirectories(project.resolve(".build/test-data"));
    try (Fixture f = new Fixture(project)) {
      if (args.length > 1 && args[1].equals("--serve")) {
        serve(project, f);
        return;
      }
      media(f);
      sessions(f);
      audit(f);
      workflows(f);
      http(f);
      migration(f);
    }
    System.out.println(
        "PASS: "
            + checks
            + " full integration checks. PNG/JPEG/GIF, crop/colors/disposal, atomic codes, glyphs,"
            + " pack ZIP/rollback, real HTTP and restart/migration.");
  }

  private static EditorOptions defaults(Fixture f) {
    return EditorOptions.defaults(f.settings.features.renderHeight, f.settings.features.ascent);
  }

  private static byte[] png() throws IOException {
    BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < 2; y++)
      for (int x = 0; x < 4; x++) image.setRGB(x, y, x < 2 ? 0xffff0000 : 0xff0000ff);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }

  private static void media(Fixture f) throws Exception {
    YamlConfiguration ru = new YamlConfiguration();
    try (Reader reader =
        new InputStreamReader(
            FullIntegrationTest.class.getClassLoader().getResourceAsStream("messages_ru.yml"),
            StandardCharsets.UTF_8)) {
      ru.load(reader);
    }
    check(
        ru.getString("general.reloaded").contains("Настройки"),
        "Russian message catalogue bundled");
    ImageDecoder decoder = new ImageDecoder();
    ProcessedMedia p = decoder.decode(png(), defaults(f), f.settings.features);
    check(
        p.format.equals("png") && p.frames.size() == 1 && p.sourceWidth == 4,
        "PNG decoded by actual format");
    check(
        p.frames.get(0).getRGB(0, 0) == 0xffff0000 && p.frames.get(0).getRGB(3, 0) == 0xff0000ff,
        "PNG pixels preserved");
    EditorOptions crop =
        new EditorOptions(
            2, 0, 2, 2, EditorOptions.Fit.STRETCH, true, true, false, false, 0, 2, 1, 0, 1, 1);
    p = decoder.decode(png(), crop, f.settings.features);
    check(
        p.frames.get(0).getRGB(0, 0) == 0xff0000ff && p.frames.get(0).getRGB(3, 0) == 0xff0000ff,
        "Crop and upscaling");
    EditorOptions flip =
        new EditorOptions(
            0, 0, 0, 0, EditorOptions.Fit.CONTAIN, true, false, true, false, 0, 2, 1, 0, 1, 1);
    check(
        decoder.decode(png(), flip, f.settings.features).frames.get(0).getRGB(0, 0) == 0xff0000ff,
        "Horizontal flip");
    EditorOptions bright =
        new EditorOptions(
            0, 0, 0, 0, EditorOptions.Fit.CONTAIN, true, false, false, false, 0, 2, 1, 1, 1, 1);
    check(
        decoder.decode(png(), bright, f.settings.features).frames.get(0).getRGB(0, 0) == 0xffffffff,
        "Color correction");
    rejects(
        () ->
            decoder.decode(
                png(),
                new EditorOptions(
                    9,
                    0,
                    1,
                    1,
                    EditorOptions.Fit.CONTAIN,
                    true,
                    false,
                    false,
                    false,
                    0,
                    2,
                    1,
                    0,
                    1,
                    1),
                f.settings.features),
        "Invalid crop rejected");
    rejects(
        () -> decoder.decode(new byte[32], defaults(f), f.settings.features),
        "Invalid image rejected");
    rejects(
        () -> decoder.decode(new byte[1048577], defaults(f), f.settings.features),
        "Upload bytes limited");
    BufferedImage wide = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(wide, "png", bytes);
    rejects(
        () -> decoder.decode(bytes.toByteArray(), defaults(f), f.settings.features),
        "Dimensions rejected before decoding");
    ProcessedMedia gif = decoder.decode(gif(), defaults(f), f.settings.features);
    check(
        gif.frames.size() == 4 && gif.sourceWidth == 4 && gif.sourceHeight == 2,
        "GIF logical canvas and frame count");
    check(Arrays.equals(gif.delays, new int[] {2, 1, 2, 1}), "GIF centiseconds quantized to ticks");
    check(
        gif.frames.get(1).getRGB(1, 0) == 0xff0000ff,
        "GIF frame offset composition: " + Integer.toHexString(gif.frames.get(1).getRGB(1, 0)));
    check(
        gif.frames.get(2).getRGB(1, 0) == 0xffff0000
            && gif.frames.get(2).getRGB(2, 0) == 0xff00ff00,
        "GIF restore to previous");
    check(
        (gif.frames.get(3).getRGB(2, 0) >>> 24) == 0
            && gif.frames.get(3).getRGB(0, 1) == 0xff0000ff,
        "GIF restore background and transparency");
    YamlConfiguration y = f.yaml;
    Object max = y.get("animation.max-frames");
    y.set("animation.max-frames", 3);
    rejects(
        () -> decoder.decode(gif(), defaults(f), new PluginSettings(y, f.root).features),
        "GIF bounded frame scanning");
    y.set("animation.max-frames", max);
    y.set("animation.enabled", false);
    rejects(
        () -> decoder.decode(gif(), defaults(f), new PluginSettings(y, f.root).features),
        "Animation disable enforced");
    y.set("animation.enabled", true);
    ProcessedMedia jpeg = decoder.decode(jpeg(), defaults(f), f.settings.features);
    check(jpeg.sourceWidth == 2 && jpeg.sourceHeight == 4, "JPEG EXIF orientation");
    TextRenderer renderer = new TextRenderer(f.settings);
    rejects(
        () -> renderer.prefixComponent("<green>line<newline>two</green>", TextFormat.MINIMESSAGE),
        "MiniMessage cannot inject a newline into a prefix");
    String json =
        GsonComponentSerializer.gson()
            .serialize(
                renderer.templateLink(
                    "<aqua><url></aqua>",
                    Collections.emptyMap(),
                    "https://example.org/editor#token"));
    check(
        json.contains("open_url") && json.contains("https://example.org/editor#token"),
        "Typed editor URL click event");
    check(
        ChatFormat.prepend("100% ", "<%1$s> %2$s").startsWith("100%%"),
        "Chat prefix percent escaping");
    check(
        NameTagView.truncate("§x§1§2§3§4§5§6ABCDE", 16).equals("§x§1§2§3§4§5§6AB"),
        "Name-tag RGB truncation");
  }

  private static void sessions(Fixture f) {
    AtomicLong clock = new AtomicLong(1000);
    SessionRegistry registry = new SessionRegistry(f.settings.features, clock::get);
    SessionRegistry.Opened a = registry.open(OWNER, "Alex", "VIP");
    check(
        a.token.length() == 43 && registry.require(a.token).owner.equals(OWNER),
        "Secure session token and binding");
    SessionRegistry.Opened b = registry.open(OWNER, "Alex", "vip");
    try {
      registry.require(a.token);
      throw new AssertionError("Old token accepted");
    } catch (HttpProblem e) {
      check(e.status == 401, "New session revokes previous link");
    }
    check(
        registry.count() == 1 && registry.require(b.token).group.equals("vip"),
        "One active session per owner");
    clock.addAndGet(31 * 60000L);
    check(registry.count() == 0, "Session expiry cleanup");
  }

  private static void audit(Fixture f) throws Exception {
    Path directory = f.root.resolve("audit-test");
    Files.createDirectory(directory);
    f.yaml.set("admin-log.rotate-size-mb", 1);
    PluginSettings settings = new PluginSettings(f.yaml, f.root);
    f.yaml.set("admin-log.rotate-size-mb", 10);
    java.util.List<String> chat = new ArrayList<>();
    AuditService service =
        new AuditService(
            directory,
            LOG,
            f.db,
            settings.features,
            (event, values) -> values.getOrDefault("text", event),
            (event, values, enabled, permission) -> {
              if (enabled.test(OWNER)) chat.add(event);
            });
    await(service.initialize());
    check(service.enabled(OWNER), "Audit enabled by default");
    await(service.toggle(OWNER, false));
    service.record("prefix-applied", Values.of("text", "quiet"));
    check(chat.isEmpty(), "Audit chat respects administrator preference");
    await(service.toggle(OWNER, true));
    service.record("prefix-applied", Values.of("text", "visible"));
    check(chat.size() == 1, "Audit chat preference can be enabled");
    String large = String.join("", Collections.nCopies(2050, "x"));
    for (int i = 0; i < 600; i++) {
      service.record("prefix-applied", Values.of("text", i + " " + large));
      if (i % 64 == 0) await(service.flush());
    }
    await(service.flush());
    check(Files.exists(directory.resolve("audit.log.1")), "Audit file rotation");
    check(
        service.recent(50).size() == 50 && service.recent(1).get(0).contains("599 "),
        "Bounded recent audit history");
    await(service.toggle(OWNER, false));
    service.record("prefix-applied", Values.of("text", "final-marker"));
    service.close();
    check(
        new String(Files.readAllBytes(directory.resolve("audit.log")), StandardCharsets.UTF_8)
            .contains("final-marker"),
        "Audit shutdown drains accepted records");
    try (AuditService reopened =
        new AuditService(
            directory,
            LOG,
            f.db,
            settings.features,
            (event, values) -> event,
            (event, values, enabled, permission) -> {})) {
      await(reopened.initialize());
      check(!reopened.enabled(OWNER), "Audit preference persists across restart");
      check(
          reopened.recent(1).get(0).contains("final-marker"),
          "Recent audit history loaded from disk");
    }
  }

  private static void workflows(Fixture f) throws Exception {
    SqliteGraphicRepository repository = new SqliteGraphicRepository(f.db);
    ImageDecoder decoder = new ImageDecoder();
    ProcessedMedia gif = decoder.decode(gif(), defaults(f), f.settings.features);
    AssetDescriptor a = f.media.store(gif(), gif, defaults(f));
    await(f.graphics.register(a));
    check(Files.isDirectory(f.root.resolve("data/assets/" + a.id)), "Immutable media stored");
    check(f.media.options(a).get("renderHeight").getAsInt() == 2, "Editing options persisted");
    GraphicService.SaveResult code =
        await(f.graphics.save(OWNER, "VIP", a.id, "<gold>[VIP]</gold>", TextFormat.MINIMESSAGE));
    check(code.code.matches("[A-Z2-9]{4}-[A-Z2-9]{4}"), "Readable random code");
    check(await(f.graphics.codeGroup(code.code)).equals("vip"), "Code target comes from database");
    failure(
        f.graphics.apply(code.code, OTHER, "vip", true),
        SaveCodeException.Reason.OWNER,
        "Owner binding even for administrators");
    failure(
        f.graphics.apply(code.code, OWNER, "member", false),
        SaveCodeException.Reason.GROUP,
        "Group binding");
    CompletableFuture<Void> first = f.graphics.apply(code.code, OWNER, "vip", false),
        second = f.graphics.apply(code.code, OWNER, "vip", false);
    await(first);
    failure(second, SaveCodeException.Reason.USED, "Concurrent single-use application");
    AssetDescriptor assigned = f.graphics.snapshot().asset("vip");
    check(
        assigned != null
            && Arrays.equals(assigned.glyphs(), new int[] {0xe000, 0xe001, 0xe002, 0xe003}),
        "Contiguous Unicode allocation");
    check(
        f.texts.find("vip").text().equals("<gold>[VIP]</gold>"),
        "Text and image caches published after commit");
    check(
        assigned.frameAt(0) == 0
            && assigned.frameAt(2) == 1
            && assigned.frameAt(3) == 2
            && assigned.frameAt(5) == 3
            && assigned.frameAt(6) == 0,
        "Tick-based animation cycle");
    PackRevision packA = await(f.packs.rebuild()), packAgain = await(f.packs.rebuild());
    check(packA.hash.equals(packAgain.hash), "Deterministic pack hash");
    check(
        packA.glyphs == 4 && packA.atlases == 1 && packA.packFormat == 6,
        "Minecraft 1.16.5 atlas pack");
    try (ZipFile zip = new ZipFile(packA.file.toFile())) {
      JsonObject font = zipJson(zip, "assets/minecraft/font/default.json");
      JsonObject provider = font.getAsJsonArray("providers").get(0).getAsJsonObject();
      check(
          provider.get("height").getAsInt() == 2 && provider.get("ascent").getAsInt() == 1,
          "Bitmap provider metrics");
      check(
          provider.getAsJsonArray("chars").get(0).getAsString().equals("\ue000\ue001"),
          "Glyph row mapping");
      try (InputStream in =
          zip.getInputStream(zip.getEntry("assets/tabprefix/textures/font/atlas-0001.png"))) {
        BufferedImage atlas = ImageIO.read(in);
        check(
            atlas.getWidth() == 8 && atlas.getHeight() == 4 && atlas.getRGB(5, 0) == 0xff0000ff,
            "Real PNG atlas pixels");
      }
      check(zip.getEntry("assets/tabprefix/font/prefix.json") != null, "Namespaced font included");
    }
    AssetDescriptor b =
        f.media.store(png(), decoder.decode(png(), defaults(f), f.settings.features), defaults(f));
    await(f.graphics.register(b));
    AssetDescriptor duplicate =
        f.media.store(png(), decoder.decode(png(), defaults(f), f.settings.features), defaults(f));
    await(f.graphics.register(duplicate));
    check(duplicate.id.equals(b.id), "Identical processed assets are deduplicated");
    GraphicService.SaveResult bCode =
        await(f.graphics.save(OWNER, "vip", b.id, null, TextFormat.LEGACY));
    f.yaml.set("glyphs.end-codepoint", 0xe003);
    f.graphics.reload(new PluginSettings(f.yaml, f.root).features);
    failure(
        f.graphics.apply(bCode.code, OWNER, "vip", false),
        SaveCodeException.Reason.CAPACITY,
        "Glyph exhaustion rolls back transaction");
    check(
        f.graphics.snapshot().asset("vip").id.equals(a.id) && f.texts.find("vip") != null,
        "Exhaustion preserves previous prefix and text");
    f.yaml.set("glyphs.end-codepoint", 0xf8ff);
    f.graphics.reload(f.settings.features);
    await(f.graphics.apply(bCode.code, OWNER, "vip", false));
    check(f.graphics.snapshot().asset("vip").glyphCode(0) == 0xe004, "Glyphs are never reused");
    check(f.texts.find("vip") == null, "Image-only application restores LuckPerms text");
    PackRevision packB = await(f.packs.rebuild());
    check(packB.glyphs == 5 && packB.atlases == 2, "New pack retains old assignments");
    PackRevision rollback = await(f.packs.rollback(packA.hash));
    check(
        rollback.assets.contains(a.id)
            && !rollback.assets.contains(b.id)
            && rollback.hash.equals(packA.hash),
        "Rollback client asset availability");
    check(
        f.graphics.snapshot().asset("vip").id.equals(b.id),
        "Pack rollback leaves group configuration intact");
    PrefixDraft expired =
        new PrefixDraft(OWNER, "vip", null, "expired", TextFormat.LEGACY, 100, 200);
    await(repository.saveDraft(expired, Digests.sha256("ABCDEFGH"), true, true));
    failure(
        repository.apply(Digests.sha256("ABCDEFGH"), OWNER, "vip", false, 0xe000, 0xf8ff, 201),
        SaveCodeException.Reason.EXPIRED,
        "Expired code refused");
    PrefixDraft reusable =
        new PrefixDraft(
            OWNER,
            "vip",
            a.id,
            null,
            TextFormat.LEGACY,
            System.currentTimeMillis(),
            System.currentTimeMillis() + 60000);
    await(repository.saveDraft(reusable, Digests.sha256("JKLMNPQR"), false, false));
    await(
        repository.apply(
            Digests.sha256("JKLMNPQR"),
            OTHER,
            "vip",
            false,
            0xe000,
            0xf8ff,
            System.currentTimeMillis()));
    await(
        repository.apply(
            Digests.sha256("JKLMNPQR"),
            OWNER,
            "vip",
            false,
            0xe000,
            0xf8ff,
            System.currentTimeMillis()));
    check(await(repository.load()).glyphs() == 5, "Reusable unbound codes do not allocate twice");
    await(f.graphics.remove("vip"));
    check(
        f.graphics.snapshot().asset("vip") == null && f.texts.find("vip") == null,
        "Removal clears custom image and text");
    check(f.graphics.snapshot().glyphs() == 5, "Removal retains permanent glyphs");
    EditorOptions abandonedOptions =
        new EditorOptions(
            0, 0, 0, 0, EditorOptions.Fit.CONTAIN, true, false, false, false, 0, 2, 1, .25, 1, 1);
    AssetDescriptor abandoned =
        f.media.store(
            png(), decoder.decode(png(), abandonedOptions, f.settings.features), abandonedOptions);
    await(f.graphics.register(abandoned));
    await(f.graphics.save(OWNER, "vip", abandoned.id, null, TextFormat.LEGACY));
    java.util.List<UUID> removed = await(f.graphics.cleanup(System.currentTimeMillis() + 7200000L));
    check(
        removed.contains(abandoned.id) && !removed.contains(a.id) && !removed.contains(b.id),
        "Unassigned expired media cleanup");
    for (UUID id : removed) f.media.delete(id);
    check(!Files.exists(f.root.resolve("data/assets/" + abandoned.id)), "Removed orphan files");
    f.yaml.set("atlas.auto-create-pages", false);
    PackBuilder limited =
        new PackBuilder(
            new PluginSettings(f.yaml, f.root),
            f.media,
            MinecraftVersion.parse("1.16.5"),
            "http://localhost");
    rejects(() -> limited.build(f.graphics.snapshot()), "Atlas capacity enforced");
    check(
        f.builder.load(f.graphics.snapshot()).hash.equals(packA.hash),
        "Failed pack build keeps active metadata");
    f.yaml.set("atlas.auto-create-pages", true);
    f.yaml.set("atlas.enabled", false);
    PackBuilder individual =
        new PackBuilder(
            new PluginSettings(f.yaml, f.root),
            f.media,
            MinecraftVersion.parse("1.16.5"),
            "http://localhost");
    check(
        individual.build(f.graphics.snapshot()).atlases == 5,
        "Individual bitmap providers without atlas");
    f.yaml.set("atlas.enabled", true);
    f.yaml.set("storage.packs-directory", "old-version-packs");
    PackRevision old =
        new PackBuilder(
                new PluginSettings(f.yaml, f.root),
                f.media,
                MinecraftVersion.parse("1.16"),
                "http://localhost")
            .build(f.graphics.snapshot());
    check(old.packFormat == 5, "Minecraft 1.16-1.16.1 pack format");
    f.yaml.set("storage.packs-directory", "packs");
    try (SqliteDatabase reopened = new SqliteDatabase(f.settings.databaseFile, LOG)) {
      await(reopened.initialize());
      GraphicSnapshot snapshot = await(new SqliteGraphicRepository(reopened).load());
      check(
          snapshot.glyphs() == 5 && !snapshot.prefixes.containsKey("vip"),
          "SQLite restart preserves allocation and removal");
    }
    PackRequestState state = new PackRequestState();
    check(
        state.offer(packA, 100) == PackRequestState.Offer.SEND
            && state.offer(packB, 101) == PackRequestState.Offer.QUEUED,
        "Resource pack requests serialized");
    check(
        state.terminal(true) == packB && state.loaded() == packA,
        "Success correlates pending pack");
    state.offer(packB, 200);
    state.terminal(false);
    check(state.loaded() == null && !state.pending(), "Decline clears client graphics state");
    state.offer(packA, 1000);
    check(
        state.timeout(121001) && state.pending() && !state.timeout(121002),
        "Timeout retains correlation for late status");
    state.terminal(true);
    check(
        state.offer(packA, 200000) == PackRequestState.Offer.SAME, "Loaded pack is not sent twice");
  }

  private static void http(Fixture f) throws Exception {
    f.http();
    String url = f.editor.open(OWNER, "Alex", "vip"),
        base = url.substring(0, url.indexOf("/editor#")),
        token = url.substring(url.indexOf('#') + 1);
    Response response = request(base + "/editor", "GET", null, null, null);
    check(
        response.status == 200
            && response.header("Content-Security-Policy").contains("script-src 'self'"),
        "Static editor with CSP");
    check(
        request(base + "/api/session", "GET", null, null, null).status == 401,
        "Unauthenticated API denied");
    response = request(base + "/api/session", "GET", token, null, null);
    check(
        response.status == 200 && response.json().get("group").getAsString().equals("vip"),
        "Authenticated session identity");
    response = request(base + "/api/upload", "POST", token, png(), null);
    check(
        response.status == 200 && response.json().get("frames").getAsInt() == 1,
        "Real HTTP image upload");
    check(
        request(base + "/api/original", "GET", token, null, null)
            .header("Content-Type")
            .equals("image/png"),
        "Detected original MIME type");
    check(
        request(base + "/api/frame/0", "GET", token, null, null).status == 200,
        "Authenticated processed frame");
    check(
        request(base + "/api/upload", "POST", token, new byte[1048577], null).status == 413,
        "HTTP upload size limit");
    check(
        request(base + "/api/upload", "POST", token, new byte[32], null).status == 400,
        "HTTP invalid image rejected");
    check(
        request(base + "/api/frame/0", "GET", token, null, null).status == 200,
        "Invalid upload preserves prior preview");
    Map<String, String> origin = new HashMap<>();
    origin.put("Origin", "https://untrusted.example");
    check(
        request(base + "/api/preview", "POST", token, "{}".getBytes(StandardCharsets.UTF_8), origin)
                .status
            == 403,
        "Cross-origin mutations blocked");
    JsonObject save = new JsonObject();
    save.addProperty("image", true);
    save.addProperty("textOverride", true);
    save.addProperty("text", "<green>[WEB]</green>");
    save.addProperty("format", "MINIMESSAGE");
    save.add("options", new JsonObject());
    response =
        request(
            base + "/api/save",
            "POST",
            token,
            save.toString().getBytes(StandardCharsets.UTF_8),
            null);
    check(
        response.status == 200 && response.json().get("code").getAsString().length() == 9,
        "HTTP draft produces save code");
    await(f.graphics.apply(response.json().get("code").getAsString(), OWNER, "vip", false));
    check(
        f.texts.find("vip").text().contains("WEB") && f.graphics.snapshot().asset("vip") != null,
        "Web-to-Minecraft transaction pipeline");
    JsonObject invalidSave = new JsonObject();
    invalidSave.addProperty("image", false);
    invalidSave.addProperty("textOverride", true);
    invalidSave.addProperty("text", "<newline>");
    invalidSave.addProperty("format", "MINIMESSAGE");
    check(
        request(
                    base + "/api/save",
                    "POST",
                    token,
                    invalidSave.toString().getBytes(StandardCharsets.UTF_8),
                    null)
                .status
            == 400,
        "Direct HTTP cannot save rendered multiline text");
    PackRevision revision = await(f.packs.rebuild());
    response = request(revision.url, "HEAD", null, null, null);
    check(
        response.status == 200
            && Long.parseLong(response.header("Content-Length")) == Files.size(revision.file),
        "Pack HEAD size");
    Map<String, String> range = new HashMap<>();
    range.put("Range", "bytes=0-127");
    response = request(revision.url, "GET", null, null, range);
    check(
        response.status == 206
            && response.bytes.length == 128
            && response.header("Content-Range").startsWith("bytes 0-127/"),
        "HTTP partial pack download");
    check(
        Arrays.equals(response.bytes, Arrays.copyOf(Files.readAllBytes(revision.file), 128)),
        "Pack range exact bytes");
    range.put("Range", "bytes=999999999-");
    check(
        request(revision.url, "GET", null, null, range).status == 416,
        "Invalid byte ranges rejected");
    check(
        request(base + "/packs/tabprefix.db", "GET", null, null, null).status == 404,
        "Private database cannot be downloaded");
    check(
        request(base + "/api/frame/1000", "GET", token, null, null).status == 404,
        "Out-of-bounds frame rejected");
    f.editor.open(OWNER, "Alex", "vip");
    check(
        request(base + "/api/session", "GET", token, null, null).status == 401,
        "HTTP token revocation");
    await(f.editor.cleanup());
    check(
        !Files.exists(f.root.resolve("temp/sessions"))
            || Files.list(f.root.resolve("temp/sessions")).count() == 0,
        "Revoked session upload cleanup");
  }

  private static void migration(Fixture f) throws Exception {
    Path path = f.root.resolve("migration.db");
    try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + path, new Properties());
        Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE group_text_prefixes(group_name TEXT PRIMARY KEY,prefix_text TEXT NOT"
              + " NULL,text_format TEXT NOT NULL,updated_by TEXT,updated_at INTEGER NOT NULL)");
      s.execute("INSERT INTO group_text_prefixes VALUES('vip','old','LEGACY',NULL,1)");
      s.execute("PRAGMA user_version=1");
    }
    try (SqliteDatabase db = new SqliteDatabase(path, LOG)) {
      await(db.initialize());
      check(
          await(new SqliteGroupPrefixRepository(db).findAll()).get(0).text().equals("old"),
          "Schema 1 migration preserves text");
      check(
          await(
                  db.execute(
                      c -> {
                        try (Statement s = c.createStatement();
                            ResultSet r = s.executeQuery("PRAGMA user_version")) {
                          r.next();
                          return r.getInt(1);
                        }
                      }))
              == 3,
          "Schema migrated to version 3");
    }
  }

  private static void serve(Path project, Fixture f) throws Exception {
    f.http();
    String url = f.editor.open(OWNER, "Alex", "vip");
    JsonObject info = new JsonObject();
    info.addProperty("url", url);
    info.addProperty("root", f.root.toString());
    Path path = project.resolve(".build/editor-test.json");
    Files.write(path, info.toString().getBytes(StandardCharsets.UTF_8));
    Files.write(project.resolve(".build/test-png.png"), png());
    Files.write(project.resolve(".build/test-gif.gif"), gif());
    System.out.println("EDITOR_TEST_READY");
    try {
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    } finally {
      Files.deleteIfExists(path);
    }
  }

  private interface Throwing {
    void run() throws Exception;
  }

  private static void rejects(Throwing work, String name) throws Exception {
    try {
      work.run();
      throw new AssertionError(name);
    } catch (IllegalArgumentException expected) {
      checks++;
    }
  }

  private static void failure(
      CompletableFuture<?> future, SaveCodeException.Reason reason, String name) throws Exception {
    try {
      await(future);
      throw new AssertionError(name);
    } catch (ExecutionException e) {
      Throwable root = Failures.root(e);
      check(root instanceof SaveCodeException && ((SaveCodeException) root).reason == reason, name);
    }
  }

  private static JsonObject zipJson(ZipFile zip, String name) throws IOException {
    try (Reader r =
        new InputStreamReader(zip.getInputStream(zip.getEntry(name)), StandardCharsets.UTF_8)) {
      return new JsonParser().parse(r).getAsJsonObject();
    }
  }

  private static byte[] jpeg() throws IOException {
    BufferedImage image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream original = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", original);
    byte[] b = original.toByteArray(), segment = new byte[36];
    segment[0] = (byte) 255;
    segment[1] = (byte) 0xe1;
    segment[3] = 34;
    segment[4] = 'E';
    segment[5] = 'x';
    segment[6] = 'i';
    segment[7] = 'f';
    segment[10] = 'I';
    segment[11] = 'I';
    segment[12] = 42;
    segment[14] = 8;
    segment[18] = 1;
    segment[20] = 0x12;
    segment[21] = 1;
    segment[22] = 3;
    segment[24] = 1;
    segment[28] = 6;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(b, 0, 2);
    out.write(segment);
    out.write(b, 2, b.length - 2);
    return out.toByteArray();
  }

  private static byte[] gif() throws IOException {
    byte[] red = {0, (byte) 255, 0, 0}, green = {0, 0, 0, (byte) 255}, blue = {0, 0, (byte) 255, 0};
    IndexColorModel palette = new IndexColorModel(8, 4, red, green, blue, 0);
    ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
      writer.setOutput(output);
      ImageWriteParam param = writer.getDefaultWriteParam();
      IIOMetadata stream = writer.getDefaultStreamMetadata(param);
      IIOMetadataNode tree = (IIOMetadataNode) stream.getAsTree("javax_imageio_gif_stream_1.0");
      IIOMetadataNode logical = node(tree, "LogicalScreenDescriptor");
      logical.setAttribute("logicalScreenWidth", "4");
      logical.setAttribute("logicalScreenHeight", "2");
      IIOMetadataNode table = node(tree, "GlobalColorTable");
      table.setAttribute("sizeOfGlobalColorTable", "4");
      table.setAttribute("backgroundColorIndex", "0");
      table.setAttribute("sortFlag", "FALSE");
      while (table.getFirstChild() != null) table.removeChild(table.getFirstChild());
      for (int i = 0; i < 4; i++) {
        IIOMetadataNode entry = new IIOMetadataNode("ColorTableEntry");
        entry.setAttribute("index", String.valueOf(i));
        entry.setAttribute("red", String.valueOf(red[i] & 255));
        entry.setAttribute("green", String.valueOf(green[i] & 255));
        entry.setAttribute("blue", String.valueOf(blue[i] & 255));
        table.appendChild(entry);
      }
      stream.setFromTree("javax_imageio_gif_stream_1.0", tree);
      writer.prepareWriteSequence(stream);
      int[] xs = {0, 1, 2, 0}, ys = {0, 0, 0, 1}, colors = {1, 2, 3, 2}, delays = {0, 1, 7, 2};
      String[] disposals = {
        "doNotDispose", "restoreToPrevious", "restoreToBackgroundColor", "doNotDispose"
      };
      for (int i = 0; i < 4; i++) {
        int w = i == 0 ? 4 : 1, h = i == 0 ? 2 : 1;
        BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, palette);
        for (int y = 0; y < h; y++)
          for (int x = 0; x < w; x++) frame.getRaster().setSample(x, y, 0, colors[i]);
        IIOMetadata meta = writer.getDefaultImageMetadata(new ImageTypeSpecifier(frame), param);
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
        IIOMetadataNode descriptor = node(root, "ImageDescriptor");
        descriptor.setAttribute("imageLeftPosition", String.valueOf(xs[i]));
        descriptor.setAttribute("imageTopPosition", String.valueOf(ys[i]));
        IIOMetadataNode control = node(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", disposals[i]);
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "TRUE");
        control.setAttribute("transparentColorIndex", "0");
        control.setAttribute("delayTime", String.valueOf(delays[i]));
        meta.setFromTree("javax_imageio_gif_image_1.0", root);
        writer.writeToSequence(new IIOImage(frame, null, meta), param);
      }
      writer.endWriteSequence();
    } finally {
      writer.dispose();
    }
    return bytes.toByteArray();
  }

  private static IIOMetadataNode node(IIOMetadataNode root, String name) {
    for (int i = 0; i < root.getLength(); i++)
      if (root.item(i).getNodeName().equals(name)) return (IIOMetadataNode) root.item(i);
    IIOMetadataNode n = new IIOMetadataNode(name);
    root.appendChild(n);
    return n;
  }

  private static final class Response {
    final int status;
    final byte[] bytes;
    final Map<String, java.util.List<String>> headers;

    Response(int status, byte[] bytes, Map<String, java.util.List<String>> headers) {
      this.status = status;
      this.bytes = bytes;
      this.headers = headers;
    }

    String header(String key) {
      for (Map.Entry<String, java.util.List<String>> entry : headers.entrySet())
        if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue().get(0);
      return "";
    }

    JsonObject json() {
      return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
  }

  private static Response request(
      String url, String method, String token, byte[] body, Map<String, String> headers)
      throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(3000);
    c.setReadTimeout(15000);
    c.setRequestMethod(method);
    if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
    if (headers != null) headers.forEach(c::setRequestProperty);
    if (body != null) {
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/octet-stream");
      c.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = c.getOutputStream()) {
        out.write(body);
      }
    }
    int status = c.getResponseCode();
    InputStream source = status >= 400 ? c.getErrorStream() : c.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (source != null)
      try (InputStream in = source) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
      }
    Map<String, java.util.List<String>> responseHeaders = c.getHeaderFields();
    c.disconnect();
    return new Response(status, out.toByteArray(), responseHeaders);
  }
}
