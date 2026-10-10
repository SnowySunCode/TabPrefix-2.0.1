package me.snowsun.tabprefix.config;

import java.net.URI;
import java.util.*;

/** All limits are validated before activating a module or applying a reload. */
public final class FeatureSettings {
  public interface Source {
    Object get(String key, Object fallback);

    default Object get(String key) {
      return get(key, null);
    }

    Set<String> keys(String path);
  }

  public final boolean images,
      alpha,
      upscale,
      animation,
      atlas,
      autoPages,
      pack,
      autoBuild,
      autoSend,
      sendOnJoin,
      resend,
      versioned,
      web,
      autoPort,
      codes,
      singleUse,
      bound,
      audit,
      auditChat,
      auditFile,
      nameTags,
      ownScoreboard,
      imageFirst,
      fallback;
  public final int maxWidth,
      maxHeight,
      maxFrames,
      minDelay,
      defaultDelay,
      period,
      cellWidth,
      cellHeight,
      renderHeight,
      ascent,
      start,
      end,
      atlasWidth,
      atlasHeight,
      keepPacks,
      port,
      sessionMinutes,
      maxSessions,
      workers,
      codeLength,
      separatorPosition,
      codeMinutes,
      keepLogs;
  public final long uploadBytes, requestBytes, decodedPixels, storageBytes, rotateBytes;
  public final double maxScale;
  public final String description,
      packBase,
      bind,
      publicHost,
      editorBase,
      codeSeparator,
      auditPermission,
      auditName,
      language;
  public final Set<String> formats, auditEvents;

  public FeatureSettings(Source y) {
    language = text(y, "general.language", "en");
    if (!language.equals("en") && !language.equals("ru")) throw bad("general.language", "en or ru");
    images = flag(y, "images.enabled", true);
    alpha = flag(y, "images.preserve-alpha", true);
    upscale = flag(y, "images.upscale.enabled", true);
    maxWidth = number(y, "images.max-width", 4096, 1, 8192);
    maxHeight = number(y, "images.max-height", 4096, 1, 8192);
    uploadBytes = mb(y, "images.max-file-size-mb", 10, 1, 32);
    decodedPixels = mb(y, "images.max-decoded-megapixels", 128, 1, 512) / 1048576L * 1000000L;
    storageBytes = mb(y, "images.max-storage-mb", 512, 16, 16384);
    maxScale = decimal(y, "images.upscale.max-scale", 4, 1, 16);
    Set<String> parsedFormats = new HashSet<>();
    Object values = y.get("images.allowed-formats");
    if (!(values instanceof List))
      throw bad("images.allowed-formats", "list of png, jpg, jpeg, gif");
    for (Object item : (List<?>) values) {
      if (!(item instanceof String)) throw bad("images.allowed-formats", "strings");
      String f = ((String) item).toLowerCase(Locale.ROOT);
      if (f.equals("jpeg")) f = "jpg";
      if (!Arrays.asList("png", "jpg", "gif").contains(f))
        throw bad("images.allowed-formats", "png, jpg, jpeg, gif");
      parsedFormats.add(f);
    }
    formats = Collections.unmodifiableSet(parsedFormats);
    if (formats.isEmpty()) throw bad("images.allowed-formats", "non-empty list");
    animation = flag(y, "animation.enabled", true);
    maxFrames = number(y, "animation.max-frames", 100, 1, 500);
    minDelay = number(y, "animation.min-frame-delay-ms", 50, 50, 60000);
    defaultDelay = number(y, "animation.default-frame-delay-ms", 100, 50, 60000);
    period = number(y, "animation.scheduler-period-ticks", 1, 1, 20);
    start = number(y, "glyphs.start-codepoint", 57344, 0xe000, 0xf8ff);
    end = number(y, "glyphs.end-codepoint", 63743, start, 0xf8ff);
    cellWidth = number(y, "glyphs.width", 32, 1, 128);
    cellHeight = number(y, "glyphs.height", 16, 1, 128);
    renderHeight = number(y, "glyphs.render-height", 16, 1, 64);
    ascent = number(y, "glyphs.ascent", 13, -64, renderHeight);
    atlas = flag(y, "atlas.enabled", true);
    autoPages = flag(y, "atlas.auto-create-pages", true);
    atlasWidth = number(y, "atlas.width", 512, cellWidth, 2048);
    atlasHeight = number(y, "atlas.height", 512, cellHeight, 2048);
    if (atlasWidth % cellWidth != 0 || atlasHeight % cellHeight != 0)
      throw bad("atlas", "dimensions divisible by glyph cell dimensions");
    if (!text(y, "atlas.image-format", "png").equalsIgnoreCase("png"))
      throw bad("atlas.image-format", "png");
    pack = flag(y, "resource-pack.enabled", true);
    autoBuild = flag(y, "resource-pack.auto-build", true);
    autoSend = flag(y, "resource-pack.auto-send", true);
    sendOnJoin = flag(y, "resource-pack.send-on-join", true);
    resend = flag(y, "resource-pack.resend-after-rebuild", true);
    versioned = flag(y, "resource-pack.versioned-files", true);
    keepPacks = number(y, "resource-pack.keep-old-packs", 3, 1, 30);
    description = text(y, "resource-pack.description", "TabPrefix Resource Pack");
    if (description.length() > 256)
      throw bad("resource-pack.description", "at most 256 characters");
    packBase = url(y, "resource-pack.public-base-url");
    web = flag(y, "web.enabled", true);
    bind = text(y, "web.bind-address", "0.0.0.0");
    port = number(y, "web.port", 8765, 0, 65535);
    autoPort = flag(y, "web.auto-port", true);
    publicHost = text(y, "web.public-host", "");
    if (publicHost.contains("/") || publicHost.contains("@") || publicHost.contains(" "))
      throw bad("web.public-host", "host name or IP address without scheme or port");
    if (!publicHost.isEmpty()) {
      String host =
          publicHost.contains(":") && !publicHost.startsWith("[")
              ? "[" + publicHost + "]"
              : publicHost;
      try {
        if (URI.create("http://" + host + ":" + port).getHost() == null)
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw bad("web.public-host", "valid host or IP without port");
      }
    }
    editorBase = url(y, "web.public-base-url");
    if (port == 0
        && (!autoPort || !publicHost.isEmpty() || !editorBase.isEmpty() || !packBase.isEmpty()))
      throw bad("web.port", "a fixed port with explicit public URLs or web.auto-port: false");
    sessionMinutes = number(y, "web.session-ttl-minutes", 30, 1, 1440);
    maxSessions = number(y, "web.max-sessions", 20, 1, 200);
    workers = number(y, "web.worker-threads", 4, 1, 16);
    requestBytes = mb(y, "web.max-request-size-mb", 12, 1, 32);
    codes = flag(y, "save-codes.enabled", true);
    singleUse = flag(y, "save-codes.single-use", true);
    bound = flag(y, "save-codes.bind-to-player", true);
    codeLength = number(y, "save-codes.length", 8, 8, 32);
    codeSeparator = text(y, "save-codes.separator", "-");
    if (!codeSeparator.matches("[-_]?")) throw bad("save-codes.separator", "-, _ or empty");
    separatorPosition = number(y, "save-codes.separator-position", 4, 1, codeLength - 1);
    codeMinutes = number(y, "save-codes.ttl-minutes", 30, 1, 1440);
    audit = flag(y, "admin-log.enabled", true);
    auditChat = flag(y, "admin-log.chat", true);
    auditFile = flag(y, "admin-log.file", true);
    auditPermission = text(y, "admin-log.permission", "tabprefix.adminlog");
    auditName = text(y, "admin-log.file-name", "audit.log");
    if (!auditName.matches("[A-Za-z0-9_.-]{1,80}")
        || auditName.equals(".")
        || auditName.equals("..")) throw bad("admin-log.file-name", "safe filename");
    rotateBytes = mb(y, "admin-log.rotate-size-mb", 10, 1, 100);
    keepLogs = number(y, "admin-log.keep-files", 3, 1, 20);
    Set<String> parsedEvents = new HashSet<>();
    for (String key : y.keys("admin-log.show"))
      if (flag(y, "admin-log.show." + key, false)) parsedEvents.add(key);
    auditEvents = Collections.unmodifiableSet(parsedEvents);
    nameTags = flag(y, "display.name-tag.enabled", false);
    ownScoreboard = flag(y, "display.use-own-scoreboard", false);
    imageFirst = flag(y, "prefix.image-before-text-prefix", true);
    fallback = flag(y, "prefix.fallback-to-text-prefix", true);
  }

  public String restartKey() {
    return Arrays.asList(
            web,
            bind,
            port,
            autoPort,
            workers,
            publicHost,
            editorBase,
            packBase,
            versioned,
            start,
            end,
            cellWidth,
            cellHeight,
            atlasWidth,
            atlasHeight,
            codeLength,
            codeSeparator,
            separatorPosition)
        .toString();
  }

  private static long mb(Source y, String k, int d, int a, int b) {
    return number(y, k, d, a, b) * 1048576L;
  }

  private static int number(Source y, String k, int d, int a, int b) {
    Object v = y.get(k, d);
    if (!(v instanceof Number) || ((Number) v).doubleValue() != ((Number) v).intValue())
      throw bad(k, "integer");
    int n = ((Number) v).intValue();
    if (n < a || n > b) throw bad(k, a + ".." + b);
    return n;
  }

  private static double decimal(Source y, String k, double d, double a, double b) {
    Object v = y.get(k, d);
    if (!(v instanceof Number)) throw bad(k, "number");
    double n = ((Number) v).doubleValue();
    if (!Double.isFinite(n) || n < a || n > b) throw bad(k, a + ".." + b);
    return n;
  }

  private static boolean flag(Source y, String k, boolean d) {
    Object v = y.get(k, d);
    if (!(v instanceof Boolean)) throw bad(k, "true or false");
    return (Boolean) v;
  }

  private static String text(Source y, String k, String d) {
    Object v = y.get(k, d);
    if (!(v instanceof String)) throw bad(k, "string");
    return ((String) v).trim();
  }

  private static String url(Source y, String k) {
    String v = text(y, k, "");
    if (v.isEmpty()) return v;
    try {
      URI u = URI.create(v);
      if (!Arrays.asList("http", "https").contains(u.getScheme())
          || u.getHost() == null
          || u.getRawUserInfo() != null
          || u.getRawQuery() != null
          || u.getRawFragment() != null) throw new IllegalArgumentException();
      while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
      return v;
    } catch (IllegalArgumentException e) {
      throw bad(k, "absolute http(s) URL without credentials, query or fragment");
    }
  }

  private static IllegalArgumentException bad(String k, String expected) {
    return new IllegalArgumentException(k + " must be " + expected + ".");
  }
}
