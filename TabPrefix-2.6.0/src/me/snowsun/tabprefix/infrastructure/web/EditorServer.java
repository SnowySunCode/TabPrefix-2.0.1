package me.snowsun.tabprefix.infrastructure.web;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.application.*;
import me.snowsun.tabprefix.application.port.AuditSink;
import me.snowsun.tabprefix.config.*;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.*;
import me.snowsun.tabprefix.infrastructure.resourcepack.ResourcePackService;
import me.snowsun.tabprefix.presentation.message.TextRenderer;
import me.snowsun.tabprefix.util.*;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/** Authenticated editor API plus strictly scoped immutable pack downloads. */
public final class EditorServer implements AutoCloseable {
  private final GraphicService graphics;
  private final PrefixService texts;
  private final MediaStore media;
  private final ResourcePackService packs;
  private final AuditSink audit;
  private final SessionRegistry sessions;
  private final WebAddresses addresses;
  private final Logger logger;
  private final Map<String, byte[]> staticFiles = new HashMap<>();
  private final AsyncWorker imageWorker = new AsyncWorker("Images", 1, 8);
  private volatile PluginSettings settings;
  private HttpServer server;
  private ThreadPoolExecutor pool;
  private volatile String state = "starting", failure = "";
  private volatile boolean listening;
  private boolean closed;
  private DisplayService displays;
  private java.util.function.BiFunction<UUID, String, CompletableFuture<Boolean>> access;

  @FunctionalInterface
  public interface DisplayPreview {
    CompletableFuture<JsonObject> render(UUID player, DisplayDesign design, long tick);
  }

  private DisplayPreview displayPreview;
  private java.util.function.Supplier<CompletableFuture<JsonArray>> displayGroups =
      () -> CompletableFuture.completedFuture(new JsonArray());

  public void displayGroups(java.util.function.Supplier<CompletableFuture<JsonArray>> groups) {
    displayGroups = groups;
  }

  public void displays(
      DisplayService service,
      java.util.function.BiFunction<UUID, String, CompletableFuture<Boolean>> access,
      DisplayPreview preview) {
    this.displays = service;
    packs.designs(() -> service.snapshot().design);
    this.access = access;
    this.displayPreview = preview;
  }

  public EditorServer(
      PluginSettings settings,
      GraphicService graphics,
      PrefixService texts,
      MediaStore media,
      ResourcePackService packs,
      AuditSink audit,
      String base,
      Logger logger)
      throws IOException {
    this(
        settings,
        graphics,
        texts,
        media,
        packs,
        audit,
        WebAddresses.fixed(settings.features, base),
        logger);
  }

  public EditorServer(
      PluginSettings settings,
      GraphicService graphics,
      PrefixService texts,
      MediaStore media,
      ResourcePackService packs,
      AuditSink audit,
      WebAddresses addresses,
      Logger logger)
      throws IOException {
    this.settings = settings;
    this.graphics = graphics;
    this.texts = texts;
    this.media = media;
    this.packs = packs;
    this.audit = audit;
    this.addresses = addresses;
    this.logger = logger;
    sessions = new SessionRegistry(settings.features);
    if (!settings.features.web) state = "disabled";
    for (String name :
        Arrays.asList(
            "editor.html",
            "editor.css",
            "editor.js",
            "studio.html",
            "studio.css",
            "studio.js",
            "dream.css",
            "dream.js",
            "workbench.css",
            "workbench.js"))
      try (InputStream in =
          EditorServer.class.getClassLoader().getResourceAsStream("web/" + name)) {
        if (in == null) throw new IOException("Missing web resource: " + name);
        staticFiles.put(name, read(in, 1048576));
      }
  }

  public synchronized void start() throws IOException {
    if (closed) throw new IllegalStateException("Web editor has been stopped.");
    if (listening) return;
    FeatureSettings f = settings.features;
    if (!f.web) return;
    try {
      server = bind(f);
      pool =
          new ThreadPoolExecutor(
              f.workers,
              f.workers,
              0,
              TimeUnit.MILLISECONDS,
              new ArrayBlockingQueue<>(64),
              r -> {
                Thread t = new Thread(r, "TabPrefix-HTTP");
                t.setDaemon(true);
                return t;
              },
              new ThreadPoolExecutor.AbortPolicy());
      server.setExecutor(pool);
      server.createContext("/", this::handle);
      server.start();
      addresses.bound(server.getAddress());
      failure = "";
      state = "listening";
      listening = true;
      logger.info("Web editor: " + diagnostic());
      if (addresses.port() != f.port && f.port != 0)
        logger.info("HTTP port " + f.port + " was occupied; selected " + addresses.port() + ".");
    } catch (IOException | RuntimeException error) {
      listening = false;
      state = error instanceof IOException ? "bind-failed" : "start-failed";
      failure = Failures.message(error).replaceAll("[\\p{Cntrl}]", " ");
      if (server != null) {
        server.stop(0);
        server = null;
      }
      if (pool != null) {
        pool.shutdownNow();
        pool = null;
      }
      throw error;
    }
  }

  private HttpServer bind(FeatureSettings f) throws IOException {
    boolean auto = addresses.automaticPort();
    int attempts = auto && f.port != 0 ? Math.min(20, 65536 - f.port) + 1 : 1;
    for (int i = 0; i < attempts; i++) {
      int candidate = auto && i == attempts - 1 && attempts > 1 ? 0 : f.port + i;
      HttpServer candidateServer = HttpServer.create();
      try {
        candidateServer.bind(new InetSocketAddress(f.bind, candidate), 64);
        return candidateServer;
      } catch (IOException | RuntimeException error) {
        candidateServer.stop(0);
        if (!(error instanceof BindException) || i == attempts - 1) throw error;
      }
    }
    throw new AssertionError("No HTTP bind attempt.");
  }

  public boolean running() {
    return listening && settings.features.web;
  }

  public WebAddresses addresses() {
    return addresses;
  }

  public String state() {
    return state;
  }

  public String failure() {
    return failure;
  }

  public String diagnostic() {
    return "state="
        + state
        + ", bind="
        + settings.features.bind
        + ", port="
        + addresses.port()
        + (running() ? ", base=" + addresses.base(null, null) : "")
        + (failure.isEmpty() ? "" : ", error=" + failure);
  }

  public int sessions() {
    return sessions.count();
  }

  public void reload(PluginSettings settings) {
    this.settings = settings;
    sessions.reload(settings.features);
  }

  public String open(UUID owner, String player, String group) {
    return open(owner, player, group, null);
  }

  public String open(UUID owner, String player, String group, InetAddress peer) {
    return open(owner, player, group, peer, false, false);
  }

  public String open(
      UUID owner,
      String player,
      String group,
      InetAddress peer,
      boolean design,
      boolean preferences) {
    if (!running()) throw new IllegalStateException("Web editor is unavailable.");
    String base = addresses.base(owner, peer);
    SessionRegistry.Opened opened = sessions.open(owner, player, group, WebAddresses.origin(base));
    opened.session.design = design;
    opened.session.preferences = preferences;
    audit.record("web-session-opened", Values.of("player", player, "group", group));
    return base + "/editor#" + opened.token;
  }

  public String openStudio(
      UUID owner, String player, InetAddress peer, boolean design, boolean preferences) {
    if (!running()) throw new IllegalStateException("Web editor is unavailable.");
    String base = addresses.base(owner, peer);
    SessionRegistry.Opened opened =
        sessions.open(owner, player, "default", WebAddresses.origin(base));
    opened.session.prefixes = false;
    opened.session.design = design;
    opened.session.preferences = preferences;
    audit.record("display-session-opened", Values.of("player", player));
    return base + "/studio#" + opened.token;
  }

  public CompletableFuture<Void> cleanup() {
    return imageWorker.submit(
        () -> {
          Path root = settings.tempDirectory.resolve("sessions");
          if (!Files.exists(root)) return null;
          Set<UUID> active = sessions.active();
          try (DirectoryStream<Path> folders = Files.newDirectoryStream(root)) {
            for (Path folder : folders) {
              UUID id;
              try {
                id = UUID.fromString(folder.getFileName().toString());
              } catch (IllegalArgumentException ignored) {
                continue;
              }
              if (!active.contains(id)) AtomicFiles.deleteTree(folder);
            }
          }
          return null;
        });
  }

  private void handle(HttpExchange exchange) {
    try {
      security(exchange);
      String path = exchange.getRequestURI().getRawPath(), method = exchange.getRequestMethod();
      if (path.startsWith("/packs/")) {
        pack(exchange, path, method);
        return;
      }
      if ("/health".equals(path)) {
        requireMethod(method, "GET");
        reply(
            exchange,
            200,
            "application/json",
            ("{\"status\":\"" + (running() ? "ok" : "disabled") + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (!settings.features.web) throw new HttpProblem(503, "Web editor is disabled.");
      if (path.equals("/studio") || path.equals("/studio.html")) {
        requireMethod(method, "GET");
        reply(exchange, 200, "text/html; charset=utf-8", staticFiles.get("studio.html"));
        return;
      }
      if (path.equals("/") || path.equals("/editor") || path.equals("/editor.html")) {
        requireMethod(method, "GET");
        reply(exchange, 200, "text/html; charset=utf-8", staticFiles.get("editor.html"));
        return;
      }
      if (path.equals("/editor.css")
          || path.equals("/editor.js")
          || path.equals("/studio.css")
          || path.equals("/studio.js")
          || path.equals("/dream.css")
          || path.equals("/dream.js")
          || path.equals("/workbench.css")
          || path.equals("/workbench.js")) {
        requireMethod(method, "GET");
        reply(
            exchange,
            200,
            path.endsWith(".css")
                ? "text/css; charset=utf-8"
                : "application/javascript; charset=utf-8",
            staticFiles.get(path.substring(1)));
        return;
      }
      if (!path.startsWith("/api/")) throw new HttpProblem(404, "Not found.");
      String authorization = exchange.getRequestHeaders().getFirst("Authorization");
      EditorSession session =
          sessions.require(
              authorization != null && authorization.startsWith("Bearer ")
                  ? authorization.substring(7)
                  : null);
      boolean mutation = method.equals("POST");
      boolean write =
          mutation && !path.equals("/api/display/preview") && !path.equals("/api/text-preview");
      if (!session.permit(System.currentTimeMillis(), write))
        throw new HttpProblem(429, "Request limit reached. Wait a minute.");
      if (mutation) {
        String supplied = exchange.getRequestHeaders().getFirst("Origin");
        if (supplied != null && !supplied.equals(session.origin))
          throw new HttpProblem(403, "Origin is not allowed.");
        if (write && !session.busy.compareAndSet(false, true))
          throw new HttpProblem(409, "Another editor operation is running.");
      }
      try {
        api(exchange, path, method, session);
      } finally {
        if (write) session.busy.set(false);
      }
    } catch (Exception | LinkageError error) {
      Throwable root = Failures.root(error);
      int status =
          root instanceof HttpProblem
              ? ((HttpProblem) root).status
              : root instanceof IllegalArgumentException
                      || root instanceof javax.imageio.IIOException
                  ? 400
                  : root instanceof RejectedExecutionException
                      ? 429
                      : root instanceof TimeoutException ? 503 : 500;
      String message =
          status == 500
              ? "Operation failed. Try again or check the server log."
              : Failures.message(root);
      if (status == 500)
        logger.log(java.util.logging.Level.WARNING, "Web editor operation failed.", root);
      try {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        json(exchange, status, json);
      } catch (IOException ignored) {
      }
    } finally {
      exchange.close();
    }
  }

  private void api(HttpExchange e, String path, String method, EditorSession session)
      throws Exception {
    if (path.startsWith("/api/display/") || path.equals("/api/preferences")) {
      displayApi(e, path, method, session);
      return;
    }
    if (!path.equals("/api/session") && !session.prefixes)
      throw new HttpProblem(403, "This session is for display settings only.");
    if (path.equals("/api/session")) {
      requireMethod(method, "GET");
      JsonObject json = new JsonObject();
      json.addProperty("player", session.player);
      json.addProperty("owner", session.owner.toString());
      json.addProperty("group", session.group);
      json.addProperty("expiresAt", session.expiresAt);
      json.addProperty("language", settings.features.language);
      json.addProperty("canDesign", session.design);
      json.addProperty("canSettings", session.preferences);
      json.addProperty("canPrefixes", session.prefixes);
      json.addProperty("imagesEnabled", settings.features.images);
      json.addProperty("minecraftVersion", settings.minecraft.toString());
      json.addProperty("bitmapFontsSupported", settings.minecraft.bitmapFonts());
      json.addProperty("nativeCanvasSupported", settings.minecraft.bitmapFonts());
      json.addProperty("canvasOrigin", "ACTION_BAR_CENTER");
      json.addProperty("legacyFont", !settings.minecraft.bitmapFonts());
      json.addProperty(
          "blankScoresSupported",
          settings.minecraft.compareTo(MinecraftVersion.parse("1.20.3")) >= 0);
      json.addProperty("rgbSupported", settings.minecraft.rgb());
      json.addProperty("teamTextLimit", settings.minecraft.teamTextLimit());
      json.addProperty("codesEnabled", settings.features.codes);
      json.addProperty(
          "maxBytes", Math.min(settings.features.uploadBytes, settings.features.requestBytes));
      json.addProperty("maxFrames", settings.features.maxFrames);
      json.addProperty("cellWidth", settings.features.cellWidth);
      json.addProperty("cellHeight", settings.features.cellHeight);
      json.addProperty("renderHeight", settings.features.renderHeight);
      json.addProperty("ascent", settings.features.ascent);
      json.addProperty("upscaleEnabled", settings.features.upscale);
      json.addProperty("maxScale", settings.features.maxScale);
      JsonArray formats = new JsonArray();
      for (String f : settings.features.formats) formats.add(f);
      json.add("formats", formats);
      GroupTextPrefix text = texts.find(session.group);
      json.addProperty("text", text == null ? "" : text.text());
      json.addProperty("format", text == null ? "MINIMESSAGE" : text.format().name());
      AssetDescriptor asset = graphics.snapshot().asset(session.group);
      json.addProperty("hasImage", asset != null);
      if (asset != null) {
        json.addProperty("renderHeight", asset.renderHeight);
        json.addProperty("ascent", asset.ascent);
        json.addProperty("frames", asset.frames());
        json.addProperty("sourceFormat", asset.sourceFormat);
        JsonArray delays = new JsonArray();
        for (int delay : asset.delays()) delays.add(delay * 50);
        json.add("delays", delays);
        json.add("options", media.options(asset));
      }
      json(e, 200, json);
      return;
    }
    if (path.equals("/api/upload")) {
      requireMethod(method, "POST");
      if (!settings.features.images) throw new HttpProblem(400, "Images are disabled.");
      ProcessedMedia preview =
          await(
              imageWorker.submit(
                  () -> {
                    byte[] source =
                        body(
                            e,
                            Math.min(
                                settings.features.uploadBytes, settings.features.requestBytes));
                    EditorOptions options =
                        EditorOptions.defaults(
                            settings.features.renderHeight, settings.features.ascent);
                    ProcessedMedia processed =
                        new ImageDecoder().decode(source, options, settings.features);
                    valid(session);
                    Path upload =
                        settings
                            .tempDirectory
                            .resolve("sessions")
                            .resolve(session.id.toString())
                            .resolve("upload.bin");
                    AtomicFiles.write(upload, source);
                    session.upload = upload;
                    session.uploadFormat = processed.format;
                    session.preview = processed;
                    return processed;
                  }));
      audit.record(
          "image-uploaded",
          Values.of(
              "player", session.player, "file", preview.format, "frames", preview.frames.size()));
      if (preview.frames.size() > 1)
        audit.record(
            "animation-processed",
            Values.of("player", session.player, "frames", preview.frames.size()));
      JsonObject result = preview(preview);
      result.addProperty("renderHeight", settings.features.renderHeight);
      result.addProperty("ascent", settings.features.ascent);
      json(e, 200, result);
      return;
    }
    if (path.equals("/api/original")) {
      requireMethod(method, "GET");
      byte[] source = source(session);
      String format = session.uploadFormat;
      AssetDescriptor asset = graphics.snapshot().asset(session.group);
      if (format == null && asset != null) format = asset.sourceFormat;
      reply(e, 200, "image/" + ("jpg".equals(format) ? "jpeg" : format), source);
      return;
    }
    if (path.startsWith("/api/frame/")) {
      requireMethod(method, "GET");
      int index;
      try {
        index = Integer.parseInt(path.substring(11));
      } catch (NumberFormatException error) {
        throw new HttpProblem(404, "Frame not found.");
      }
      ProcessedMedia preview = session.preview;
      BufferedImage frame;
      if (preview != null) {
        if (index < 0 || index >= preview.frames.size())
          throw new HttpProblem(404, "Frame not found.");
        frame = preview.frames.get(index);
      } else {
        AssetDescriptor asset = graphics.snapshot().asset(session.group);
        if (asset == null || index < 0 || index >= asset.frames())
          throw new HttpProblem(404, "Frame not found.");
        frame = media.frame(asset, index);
      }
      ByteArrayOutputStream png = new ByteArrayOutputStream();
      ImageIO.write(frame, "png", png);
      reply(e, 200, "image/png", png.toByteArray());
      return;
    }
    if (path.equals("/api/preview")) {
      requireMethod(method, "POST");
      EditorOptions options = options(jsonBody(e));
      ProcessedMedia result =
          await(
              imageWorker.submit(
                  () -> {
                    ProcessedMedia decoded =
                        new ImageDecoder().decode(source(session), options, settings.features);
                    valid(session);
                    session.preview = decoded;
                    return decoded;
                  }));
      json(e, 200, preview(result));
      return;
    }
    if (path.equals("/api/text-preview")) {
      requireMethod(method, "POST");
      JsonObject body = jsonBody(e);
      String text = string(body, "text", "");
      TextFormat format = TextFormat.valueOf(string(body, "format", "MINIMESSAGE"));
      if (!text.isEmpty())
        new GroupTextPrefix(session.group, text, format, session.owner, System.currentTimeMillis());
      JsonObject result = new JsonObject();
      result.add(
          "component",
          new JsonParser()
              .parse(
                  GsonComponentSerializer.gson()
                      .serialize(new TextRenderer(settings).prefixComponent(text, format))));
      json(e, 200, result);
      return;
    }
    if (path.equals("/api/save")) {
      requireMethod(method, "POST");
      if (!settings.features.codes) throw new HttpProblem(400, "Save codes are disabled.");
      JsonObject body = jsonBody(e);
      boolean image = bool(body, "image", false), override = bool(body, "textOverride", false);
      String text = override ? string(body, "text", "") : null;
      TextFormat format = TextFormat.valueOf(string(body, "format", "MINIMESSAGE"));
      if (text != null) {
        new GroupTextPrefix(session.group, text, format, session.owner, System.currentTimeMillis());
        new TextRenderer(settings).prefixComponent(text, format);
      }
      if (!image && text == null) throw new HttpProblem(400, "Choose an image or a text prefix.");
      EditorOptions options =
          options(body.has("options") ? body.getAsJsonObject("options") : new JsonObject());
      GraphicService.SaveResult result =
          await(
              imageWorker.submit(
                  () -> {
                    UUID asset = null;
                    if (image) {
                      byte[] source = source(session);
                      ProcessedMedia processed =
                          new ImageDecoder().decode(source, options, settings.features);
                      valid(session);
                      AssetDescriptor stored = media.store(source, processed, options);
                      try {
                        await(graphics.register(stored));
                        asset = stored.id;
                      } catch (Exception error) {
                        media.delete(stored.id);
                        throw error;
                      }
                    }
                    valid(session);
                    return await(graphics.save(session.owner, session.group, asset, text, format));
                  }));
      audit.record("draft-saved", Values.of("player", session.player, "group", session.group));
      audit.record("save-code-created", Values.of("player", session.player));
      JsonObject saved = new JsonObject();
      saved.addProperty("code", result.code);
      saved.addProperty("command", "/lptab webpref " + result.code);
      saved.addProperty("expiresAt", result.expiresAt);
      json(e, 200, saved);
      return;
    }
    throw new HttpProblem(404, "API route not found.");
  }

  private void displayAccess(EditorSession session, boolean design) throws Exception {
    valid(session);
    if (displays == null || access == null)
      throw new HttpProblem(503, "Display studio is unavailable.");
    if (!(design ? session.design : session.preferences)
        || !await(access.apply(session.owner, design ? "tabprefix.design" : "tabprefix.settings")))
      throw new HttpProblem(
          403, "You must be online with the required permission. Open a new session in Minecraft.");
  }

  private void displayApi(HttpExchange e, String path, String method, EditorSession session)
      throws Exception {
    if (path.equals("/api/display/assets")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      JsonArray list = new JsonArray();
      List<AssetDescriptor> assets = new ArrayList<>(graphics.snapshot().assets.values());
      assets.sort(Comparator.comparing(a -> a.id.toString()));
      for (AssetDescriptor asset : assets)
        if (asset.assigned()) {
          JsonObject j = new JsonObject();
          j.addProperty("id", asset.id.toString());
          j.addProperty("frames", asset.frames());
          j.addProperty("glyph", String.valueOf((char) asset.glyphCode(0)));
          JsonArray groups = new JsonArray();
          for (Map.Entry<String, GraphicPrefix> item : graphics.snapshot().prefixes.entrySet())
            if (asset.id.equals(item.getValue().assetId)) groups.add(item.getKey());
          j.add("groups", groups);
          list.add(j);
        }
      json(e, 200, list);
      return;
    }
    if (path.startsWith("/api/display/asset/")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      UUID id;
      try {
        id = UUID.fromString(path.substring("/api/display/asset/".length()));
      } catch (IllegalArgumentException ex) {
        throw new HttpProblem(404, "Image not found");
      }
      AssetDescriptor asset = graphics.snapshot().assets.get(id);
      if (asset == null || !asset.assigned()) throw new HttpProblem(404, "Image not found");
      byte[] png =
          await(
              imageWorker.submit(
                  () -> {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(media.frame(asset, 0), "png", out);
                    return out.toByteArray();
                  }));
      reply(e, 200, "image/png", png);
      return;
    }
    if (path.equals("/api/display/image")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      if (!settings.features.images) throw new HttpProblem(403, "Images disabled");
      byte[] bytes =
          body(e, Math.min(settings.features.uploadBytes, settings.features.requestBytes));
      AssetDescriptor stored =
          await(
              imageWorker.submit(
                  () -> {
                    EditorOptions options =
                        EditorOptions.defaults(
                            settings.features.renderHeight, settings.features.ascent);
                    ProcessedMedia processed =
                        new ImageDecoder().decode(bytes, options, settings.features);
                    valid(session);
                    return media.store(bytes, processed, options);
                  }));
      displayAccess(session, true);
      AssetDescriptor asset = await(graphics.registerScreen(stored));
      packs.rebuild();
      JsonObject j = new JsonObject();
      j.addProperty("id", asset.id.toString());
      j.addProperty("frames", asset.frames());
      j.addProperty("glyph", String.valueOf((char) asset.glyphCode(0)));
      j.add("groups", new JsonArray());
      json(e, 200, j);
      audit.record("screen-image-created", Values.of("player", session.player, "asset", asset.id));
      return;
    }
    if (path.equals("/api/display/font-baseline")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      byte[] bytes = body(e, 65536);
      displayAccess(session, true);
      new me.snowsun.tabprefix.infrastructure.resourcepack.LegacyFontAssets(
              settings.dataDirectory.resolve("legacy-font"))
          .importWidths(bytes);
      packs.rebuild();
      JsonObject j = new JsonObject();
      j.addProperty("saved", true);
      json(e, 200, j);
      return;
    }
    if (path.equals("/api/display/pack") || path.equals("/api/display/pack/rebuild")) {
      displayAccess(session, true);
      PackRevision pack;
      if (path.endsWith("rebuild")) {
        requireMethod(method, "POST");
        pack = await(packs.rebuild());
      } else {
        requireMethod(method, "GET");
        pack = packs.active();
      }
      JsonObject j = new JsonObject();
      j.addProperty(
          "format",
          me.snowsun.tabprefix.domain.ResourcePackFormat.forVersion(settings.minecraft).toString());
      j.addProperty("legacy", !settings.minecraft.bitmapFonts());
      j.addProperty(
          "fontCached",
          new me.snowsun.tabprefix.infrastructure.resourcepack.LegacyFontAssets(
                  settings.dataDirectory.resolve("legacy-font"))
              .cached());
      j.addProperty("error", packs.legacyError());
      j.addProperty("building", packs.building());
      if (pack != null) {
        j.addProperty("hash", pack.hash);
        j.addProperty("glyphs", pack.glyphs);
        j.addProperty("pages", pack.atlases);
        j.addProperty("hudProfiles", pack.hud.profiles.size());
      }
      json(e, 200, j);
      return;
    }
    if (path.equals("/api/display/pack/download")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      PackRevision pack = packs.active();
      if (pack == null) throw new HttpProblem(404, "Build a pack first");
      packs.pin(pack);
      e.getResponseHeaders()
          .set("Content-Disposition", "attachment; filename=TabPrefix-resource-pack.zip");
      pack(e, "/packs/TabPrefix-" + pack.hash + ".zip", "GET");
      return;
    }
    if (path.equals("/api/display/prefix-editor")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      if (!await(access.apply(session.owner, "tabprefix.webeditor")))
        throw new HttpProblem(403, "Prefix editor permission required");
      String group = GroupTextPrefix.normalizeGroup(string(jsonBody(e), "group", session.group));
      if (!group.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid group");
      if (!group.equals(session.group)
          && !await(access.apply(session.owner, "tabprefix.prefix.manage")))
        throw new HttpProblem(403, "Group management permission required");
      JsonObject j = new JsonObject();
      j.addProperty(
          "url", open(session.owner, session.player, group, null, true, session.preferences));
      json(e, 200, j);
      return;
    }
    if (path.equals("/api/display/groups")) {
      displayAccess(session, true);
      requireMethod(method, "GET");
      json(e, 200, await(displayGroups.get()));
      return;
    }
    if (path.equals("/api/preferences")) {
      displayAccess(session, false);
      if (method.equals("GET")) {
        json(e, 200, displays.preferences(session.owner).json());
        return;
      }
      requireMethod(method, "POST");
      PlayerDisplaySettings candidate = new PlayerDisplaySettings(jsonBody(e));
      displayAccess(session, false);
      json(e, 200, await(displays.savePreferences(session.owner, candidate)).json());
      audit.record("display-preferences-saved", Values.of("player", session.player));
      return;
    }
    if (path.equals("/api/display/design")) {
      displayAccess(session, true);
      if (method.equals("GET")) {
        json(e, 200, displays.snapshot().json());
        return;
      }
      requireMethod(method, "POST");
      JsonObject j = jsonBody(e);
      DisplayDesign candidate = new DisplayDesign(DisplayDesign.object(j.get("design"), "design"));
      validateDisplay(candidate);
      int revision = DisplayDesign.integer(j, "revision", -1, 1, Integer.MAX_VALUE - 1);
      displayAccess(session, true);
      JsonObject saved = await(displays.save(candidate, revision, session.owner)).json();
      if (settings.features.pack
          && (candidate.sidebarEnabled && candidate.sidebarMode.equals("HUD")
              || candidate.screenEnabled
                  && candidate.screen.stream()
                      .anyMatch(row -> row.enabled && row.anchor.equals("FREE_XY"))))
        packs.rebuild();
      json(e, 200, saved);
      audit.record(
          "display-design-saved", Values.of("player", session.player, "revision", revision + 1));
      return;
    }
    if (path.equals("/api/display/preview")) {
      displayAccess(session, true);
      requireMethod(method, "POST");
      JsonObject j = jsonBody(e);
      DisplayDesign candidate = new DisplayDesign(DisplayDesign.object(j.get("design"), "design"));
      validateDisplay(candidate);
      int tick = DisplayDesign.integer(j, "tick", 0, 0, 100000000);
      if (displayPreview == null) throw new HttpProblem(503, "Server preview is unavailable.");
      json(e, 200, await(displayPreview.render(session.owner, candidate, tick)));
      return;
    }
    throw new HttpProblem(404, "Display route not found.");
  }

  private void validateDisplay(DisplayDesign design) {
    TextRenderer renderer = new TextRenderer(settings);
    Map<String, String> vars = new LinkedHashMap<>();
    for (String key : Arrays.asList("player", "group", "server", "world")) vars.put(key, "Example");
    vars.put("prefix", "§6[VIP] ");
    vars.put("suffix", "§6 ★");
    vars.put("display_name", "Example");
    vars.put("tab_name", "Example");
    vars.put("weight", "1");
    for (String key :
        Arrays.asList(
            "online",
            "max",
            "ping",
            "time",
            "date",
            "health",
            "max_health",
            "food",
            "experience",
            "progress",
            "remaining",
            "level",
            "x",
            "y",
            "z",
            "layout_overflow")) vars.put(key, "1");
    List<String> sources = new ArrayList<>();
    sources.add(design.title);
    sources.add(design.nameTagPrefix);
    sources.add(design.nameTagSuffix);
    List<DisplayDesign.Line> lines = new ArrayList<>();
    lines.addAll(design.header);
    lines.addAll(design.footer);
    lines.addAll(design.sidebar);
    lines.add(design.tabPlayerFormat);
    for (DisplayDesign.GroupStyle style : design.groupStyles) lines.add(style.suffix);
    for (DisplayDesign.Boss bar : design.bossBars) lines.add(bar.line);
    for (DisplayDesign.Screen item : design.screen) lines.add(item.line);
    for (DisplayDesign.Slot slot : design.slots) lines.add(slot.line);
    for (DisplayDesign.Line line : lines) {
      sources.add(line.text);
      sources.addAll(line.frames);
    }
    for (String source : sources) {
      String plain = renderer.plain(renderer.display(source, vars));
      for (int i = 0; i < plain.length(); i++)
        if (Character.isISOControl(plain.charAt(i)))
          throw new IllegalArgumentException(
              "Each display block must render as one line without control characters.");
    }
  }

  private byte[] source(EditorSession session) throws IOException {
    valid(session);
    if (session.upload != null) {
      if (Files.size(session.upload) > settings.features.uploadBytes)
        throw new HttpProblem(413, "Original exceeds upload limit.");
      return Files.readAllBytes(session.upload);
    }
    AssetDescriptor asset = graphics.snapshot().asset(session.group);
    if (asset == null) throw new HttpProblem(400, "Upload an image first.");
    return media.source(asset);
  }

  private static void valid(EditorSession session) {
    if (session.revoked || session.expiresAt <= System.currentTimeMillis())
      throw new HttpProblem(401, "Editor session expired.");
  }

  private EditorOptions options(JsonObject j) {
    FeatureSettings f = settings.features;
    return new EditorOptions(
        integer(j, "x", 0),
        integer(j, "y", 0),
        integer(j, "width", 0),
        integer(j, "height", 0),
        EditorOptions.Fit.valueOf(string(j, "fit", "CONTAIN").toUpperCase(Locale.ROOT)),
        bool(j, "pixelArt", true),
        bool(j, "upscale", false),
        bool(j, "flipX", false),
        bool(j, "flipY", false),
        integer(j, "rotation", 0),
        integer(j, "renderHeight", f.renderHeight),
        integer(j, "ascent", f.ascent),
        decimal(j, "brightness", 0),
        decimal(j, "contrast", 1),
        decimal(j, "saturation", 1));
  }

  private static JsonObject preview(ProcessedMedia p) {
    JsonObject j = new JsonObject();
    j.addProperty("sourceWidth", p.sourceWidth);
    j.addProperty("sourceHeight", p.sourceHeight);
    j.addProperty("frames", p.frames.size());
    j.addProperty("format", p.format);
    JsonArray delays = new JsonArray();
    for (int d : p.delays) delays.add(d * 50);
    j.add("delays", delays);
    return j;
  }

  private void pack(HttpExchange e, String path, String method) throws IOException {
    if (!method.equals("GET") && !method.equals("HEAD"))
      throw new HttpProblem(405, "Expected GET or HEAD.");
    String name = path.substring(7);
    if (name.equals("TabPrefix.zip")) {
      String query = e.getRequestURI().getRawQuery();
      if (query == null || !query.matches("hash=[a-f0-9]{40}"))
        throw new HttpProblem(404, "Pack not found.");
      name = "TabPrefix-" + query.substring(5) + ".zip";
    }
    if (!name.matches("TabPrefix-[a-f0-9]{40}\\.zip"))
      throw new HttpProblem(404, "Pack not found.");
    Path file = settings.packsDirectory.resolve(name);
    if (!settings.features.pack || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new HttpProblem(404, "Pack not found.");
    long size = Files.size(file), start = 0, end = size - 1;
    int status = 200;
    String range = e.getRequestHeaders().getFirst("Range");
    if (range != null) {
      if (!range.matches("bytes=[0-9]*-[0-9]*")) throw range(size, e);
      String[] parts = range.substring(6).split("-", -1);
      try {
        if (parts[0].isEmpty()) {
          long suffix = Long.parseLong(parts[1]);
          if (suffix < 1) throw new NumberFormatException();
          start = Math.max(0, size - suffix);
        } else {
          start = Long.parseLong(parts[0]);
          if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
        }
        if (start < 0 || start >= size || end < start) throw new NumberFormatException();
      } catch (NumberFormatException error) {
        throw range(size, e);
      }
      status = 206;
      e.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
    }
    e.getResponseHeaders().set("Content-Type", "application/zip");
    e.getResponseHeaders()
        .set(
            "Cache-Control",
            e.getRequestURI().getPath().startsWith("/api/")
                ? "private, no-store"
                : "public, max-age=31536000, immutable");
    e.getResponseHeaders().set("ETag", "\"" + name.substring(10, 50) + "\"");
    e.getResponseHeaders().set("Accept-Ranges", "bytes");
    long length = end - start + 1;
    e.getResponseHeaders().set("Content-Length", String.valueOf(length));
    e.sendResponseHeaders(status, method.equals("HEAD") ? -1 : length);
    if (!method.equals("HEAD"))
      try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r");
          OutputStream output = e.getResponseBody()) {
        input.seek(start);
        byte[] buffer = new byte[32768];
        while (length > 0) {
          int n = input.read(buffer, 0, (int) Math.min(buffer.length, length));
          if (n < 0) throw new EOFException();
          output.write(buffer, 0, n);
          length -= n;
        }
      }
  }

  private static HttpProblem range(long size, HttpExchange e) {
    e.getResponseHeaders().set("Content-Range", "bytes */" + size);
    return new HttpProblem(416, "Invalid byte range.");
  }

  private static void security(HttpExchange e) {
    Headers h = e.getResponseHeaders();
    h.set(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'"
            + " blob: data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none';"
            + " form-action 'none'");
    h.set("X-Content-Type-Options", "nosniff");
    h.set("Referrer-Policy", "no-referrer");
    h.set("Cache-Control", "no-store");
  }

  private static void requireMethod(String actual, String expected) {
    if (!actual.equals(expected)) throw new HttpProblem(405, "Expected " + expected + ".");
  }

  private static byte[] body(HttpExchange e, long limit) throws IOException {
    String length = e.getRequestHeaders().getFirst("Content-Length");
    if (length != null) {
      try {
        long value = Long.parseLong(length);
        if (value < 0 || value > limit) {
          if (value > limit && value <= limit + 65536) {
            byte[] discard = new byte[8192];
            long remaining = value;
            while (remaining > 0) {
              int n =
                  e.getRequestBody().read(discard, 0, (int) Math.min(remaining, discard.length));
              if (n < 0) break;
              remaining -= n;
            }
          }
          throw new HttpProblem(413, "Request exceeds upload limit.");
        }
      } catch (NumberFormatException error) {
        throw new HttpProblem(400, "Invalid content length.");
      }
    }
    return read(e.getRequestBody(), limit);
  }

  private static byte[] read(InputStream input, long limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int n;
    while ((n = input.read(buffer)) != -1) {
      if ((long) out.size() + n > limit)
        throw new HttpProblem(413, "Request exceeds upload limit.");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private static JsonObject jsonBody(HttpExchange e) throws IOException {
    try {
      return new JsonParser()
          .parse(new String(body(e, 65536), StandardCharsets.UTF_8))
          .getAsJsonObject();
    } catch (JsonParseException | IllegalStateException error) {
      throw new HttpProblem(400, "Invalid JSON object.");
    }
  }

  private static boolean bool(JsonObject j, String key, boolean fallback) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("Expected boolean: " + key);
    return e.getAsBoolean();
  }

  private static String string(JsonObject j, String key, String fallback) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("Expected string: " + key);
    return e.getAsString();
  }

  private static int integer(JsonObject j, String key, int fallback) {
    double n = decimal(j, key, fallback);
    if (n != Math.rint(n) || n < Integer.MIN_VALUE || n > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Expected integer: " + key);
    return (int) n;
  }

  private static double decimal(JsonObject j, String key, double fallback) {
    if (!j.has(key)) return fallback;
    JsonElement e = j.get(key);
    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
      throw new IllegalArgumentException("Expected number: " + key);
    double n = e.getAsDouble();
    if (!Double.isFinite(n)) throw new IllegalArgumentException("Expected finite number: " + key);
    return n;
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(30, TimeUnit.SECONDS);
  }

  private static void json(HttpExchange e, int status, JsonElement json) throws IOException {
    reply(
        e,
        status,
        "application/json; charset=utf-8",
        json.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void reply(HttpExchange e, int status, String type, byte[] bytes)
      throws IOException {
    e.getResponseHeaders().set("Content-Type", type);
    e.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = e.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    listening = false;
    state = "stopped";
    addresses.clear();
    sessions.close();
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (pool != null) pool.shutdownNow();
    imageWorker.close();
  }
}
