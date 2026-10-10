package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.util.*;

/** Deterministic ZIP generation. Old glyph assignments remain in every new pack. */
public final class PackBuilder {
  private volatile String legacyError;

  public String legacyError() {
    return legacyError;
  }

  private final MediaStore media;
  private final MinecraftVersion version;
  private final Supplier<String> publicBase;
  private final Map<String, Long> pins = new ConcurrentHashMap<>();
  private volatile PluginSettings settings;
  private volatile Supplier<DisplayDesign> designs = () -> null;

  public void designs(Supplier<DisplayDesign> designs) {
    this.designs = java.util.Objects.requireNonNull(designs);
  }

  public PackBuilder(
      PluginSettings settings, MediaStore media, MinecraftVersion version, String publicBase) {
    this(settings, media, version, () -> publicBase);
  }

  public PackBuilder(
      PluginSettings settings,
      MediaStore media,
      MinecraftVersion version,
      Supplier<String> publicBase) {
    this.settings = settings;
    this.media = media;
    this.version = version;
    this.publicBase = publicBase;
  }

  public void reload(PluginSettings settings) {
    this.settings = settings;
  }

  public void pin(String hash) {
    pins.put(hash, System.currentTimeMillis() + 300000L);
  }

  public PackRevision build(GraphicSnapshot snapshot) throws IOException {
    PluginSettings s = settings;
    me.snowsun.tabprefix.config.FeatureSettings f = s.features;
    if (!f.pack) throw new IllegalStateException("Resource packs are disabled.");
    Map<String, byte[]> entries = new TreeMap<>();
    JsonArray providers = new JsonArray();
    Map<String, java.util.List<Glyph>> profiles = new TreeMap<>();
    Set<UUID> included = new HashSet<>();
    int count = 0;
    for (AssetDescriptor a : snapshot.assets.values())
      if (version.bitmapFonts() && a.assigned()) {
        included.add(a.id);
        for (int i = 0; i < a.frames(); i++) {
          profiles
              .computeIfAbsent(a.renderHeight + ":" + a.ascent, k -> new ArrayList<>())
              .add(new Glyph(a, i));
          count++;
        }
      }
    int pages = 0;
    for (java.util.List<Glyph> profile : profiles.values()) {
      profile.sort(Comparator.comparingInt(g -> g.asset.glyphCode(g.frame)));
      int cols = f.atlas ? f.atlasWidth / f.cellWidth : 1,
          rows = f.atlas ? f.atlasHeight / f.cellHeight : 1,
          capacity = cols * rows;
      for (int base = 0; base < profile.size(); base += capacity) {
        pages++;
        if (f.atlas && !f.autoPages && pages > 1)
          throw new IllegalArgumentException("Atlas is full and automatic pages are disabled.");
        int length = Math.min(capacity, profile.size() - base),
            usedRows = (length + cols - 1) / cols;
        BufferedImage atlas =
            new BufferedImage(
                f.atlas ? f.atlasWidth : f.cellWidth,
                f.atlas ? f.atlasHeight : f.cellHeight,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = atlas.createGraphics();
        JsonArray chars = new JsonArray();
        try {
          for (int y = 0; y < (f.atlas ? rows : usedRows); y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < cols; x++) {
              int index = y * cols + x;
              if (index < length) {
                Glyph g = profile.get(base + index);
                if (g.asset.width != f.cellWidth || g.asset.height != f.cellHeight)
                  throw new IOException("Asset cell size differs from configured geometry.");
                graphics.drawImage(
                    media.frame(g.asset, g.frame), x * f.cellWidth, y * f.cellHeight, null);
                line.append((char) g.asset.glyphCode(g.frame));
              } else line.append('\u0000');
            }
            chars.add(line.toString());
          }
        } finally {
          graphics.dispose();
        }
        String name = String.format(Locale.ROOT, "atlas-%04d.png", pages);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(atlas, "png", png)) throw new IOException("PNG writer unavailable.");
        entries.put("assets/tabprefix/textures/font/" + name, png.toByteArray());
        AssetDescriptor metric = profile.get(0).asset;
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "bitmap");
        provider.addProperty("file", "tabprefix:font/" + name);
        provider.addProperty("height", metric.renderHeight);
        provider.addProperty("ascent", metric.ascent);
        provider.add("chars", chars);
        providers.add(provider);
      }
    }
    JsonObject prefixFont = new JsonObject();
    prefixFont.add("providers", new JsonParser().parse(providers.toString()));
    NativeHudLayout hud =
        NativeHudFonts.generate(entries, providers, designs.get(), snapshot, media, version);
    if (hud.present())
      entries.put("tabprefix-hud.json", hud.json().toString().getBytes(StandardCharsets.UTF_8));
    JsonObject font = new JsonObject();
    font.add("providers", providers);
    byte[] json = font.toString().getBytes(StandardCharsets.UTF_8);
    if (version.bitmapFonts()) {
      entries.put("assets/minecraft/font/default.json", json);
      entries.put(
          "assets/tabprefix/font/prefix.json",
          prefixFont.toString().getBytes(StandardCharsets.UTF_8));
    }
    if (!version.bitmapFonts())
      try {
        pages =
            LegacyUnicodePack.generate(
                entries,
                snapshot,
                media,
                new LegacyFontAssets(s.dataDirectory.resolve("legacy-font")));
        for (AssetDescriptor a : snapshot.assets.values())
          if (a.assigned()) {
            included.add(a.id);
            count += a.frames();
          }
        legacyError = null;
      } catch (LegacyFontAssets.Unavailable e) {
        legacyError = e.getMessage();
        entries.clear();
        count = 0;
        pages = 0;
        included.clear();
        java.util.logging.Logger.getLogger("TabPrefix")
            .warning("1.12 graphics use text until original fonts are available: " + legacyError);
      }
    ResourcePackFormat format = ResourcePackFormat.forVersion(version);
    JsonObject meta = new JsonObject();
    meta.add("pack", format.metadata(f.description));
    entries.put("pack.mcmeta", meta.toString().getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(s.stagingDirectory);
    Files.createDirectories(s.packsDirectory);
    Path temporary = Files.createTempFile(s.stagingDirectory, "pack-", ".zip");
    try {
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
        zip.setLevel(9);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
          ZipEntry item = new ZipEntry(e.getKey());
          item.setTime(0L);
          zip.putNextEntry(item);
          zip.write(e.getValue());
          zip.closeEntry();
        }
      }
      String hash = Digests.hex("SHA-1", Files.readAllBytes(temporary));
      Path target = s.packsDirectory.resolve("TabPrefix-" + hash + ".zip");
      if (Files.exists(target)) Files.delete(temporary);
      else AtomicFiles.move(temporary, target);
      PackRevision revision =
          new PackRevision(
              hash,
              url(hash),
              target,
              included,
              count,
              pages,
              format.major,
              System.currentTimeMillis(),
              hud);
      publish(revision);
      prune(revision);
      return revision;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public PackRevision load(GraphicSnapshot snapshot) throws IOException {
    Path path = settings.packsDirectory.resolve("active.json");
    if (!Files.exists(path))
      return settings.features.pack && version.bitmapFonts() && needsCanvas(designs.get())
          ? build(snapshot)
          : null;
    if (Files.size(path) > 65536) throw new IOException("Invalid active pack metadata.");
    JsonObject info =
        new JsonParser()
            .parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
            .getAsJsonObject();
    try {
      PackRevision revision =
          inspect(info.get("hash").getAsString(), snapshot, info.get("createdAt").getAsLong());
      DisplayDesign design = designs.get();
      return settings.features.pack
              && version.bitmapFonts()
              && design != null
              && !revision.hud.matches(design)
          ? build(snapshot)
          : revision;
    } catch (FormatChanged changed) {
      // A server upgrade changes pack metadata, not permanent glyph assignments or media.
      return settings.features.pack ? build(snapshot) : null;
    }
  }

  public PackRevision rollback(String hash, GraphicSnapshot snapshot) throws IOException {
    PackRevision revision = inspect(hash, snapshot, System.currentTimeMillis());
    publish(revision);
    return revision;
  }

  private PackRevision inspect(String hash, GraphicSnapshot snapshot, long time)
      throws IOException {
    if (hash == null || !hash.matches("[a-f0-9]{40}"))
      throw new IllegalArgumentException("Expected a complete SHA-1 hash.");
    Path file = settings.packsDirectory.resolve("TabPrefix-" + hash + ".zip");
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || !hash.equals(Digests.hex("SHA-1", Files.readAllBytes(file))))
      throw new IOException("Pack missing or hash mismatch.");
    Set<Integer> codes = new HashSet<>(), canvasCodes = new HashSet<>();
    NativeHudLayout hud = NativeHudLayout.EMPTY;
    ResourcePackFormat expected = ResourcePackFormat.forVersion(version);
    int format = expected.major, pages = 0;
    try (ZipFile zip = new ZipFile(file.toFile())) {
      JsonObject metadata = readJson(zip, "pack.mcmeta");
      if (!expected.matches(metadata.getAsJsonObject("pack")))
        throw new FormatChanged(
            "Pack format differs from Minecraft " + version + ": expected " + expected);
      if (version.bitmapFonts()) {
        JsonArray providers =
            readJson(zip, "assets/minecraft/font/default.json").getAsJsonArray("providers");
        for (JsonElement element : providers) {
          JsonObject provider = element.getAsJsonObject();
          if (provider.get("type").getAsString().equals("space")) {
            for (Map.Entry<String, JsonElement> e : provider.getAsJsonObject("advances").entrySet())
              canvasCodes.add(e.getKey().codePointAt(0));
            continue;
          }
          String texture = provider.get("file").getAsString();
          if (!texture.startsWith("tabprefix:font/")
              || zip.getEntry(
                      "assets/tabprefix/textures/" + texture.substring("tabprefix:".length()))
                  == null) throw new IOException("Missing pack texture.");
          boolean canvas = texture.startsWith("tabprefix:font/hud-");
          if (!canvas) pages++;
          for (JsonElement row : provider.getAsJsonArray("chars"))
            row.getAsString()
                .codePoints()
                .filter(c -> c != 0)
                .forEach(c -> (canvas ? canvasCodes : codes).add(c));
        }
      } else if (zip.getEntry("tabprefix-glyphs.json") != null) {
        ZipEntry metrics = zip.getEntry("assets/minecraft/font/glyph_sizes.bin");
        if (metrics == null || metrics.getSize() != 65536)
          throw new IOException("Invalid legacy metrics");
        byte[] widths = new byte[65536];
        try (DataInputStream in = new DataInputStream(zip.getInputStream(metrics))) {
          in.readFully(widths);
        }
        Set<Integer> pageIds = new HashSet<>();
        for (JsonElement item : readJson(zip, "tabprefix-glyphs.json").getAsJsonArray("glyphs")) {
          int c = item.getAsJsonObject().get("codepoint").getAsInt();
          if (c < 0xe000 || c > 0xf8ff || widths[c] == 0)
            throw new IOException("Invalid legacy glyph");
          if (zip.getEntry(
                  String.format(
                      Locale.ROOT, "assets/minecraft/textures/font/unicode_page_%02x.png", c >> 8))
              == null) throw new IOException("Legacy page missing");
          codes.add(c);
          pageIds.add(c >> 8);
        }
        pages = pageIds.size();
      } else if (!snapshot.assets.isEmpty()
          && new LegacyFontAssets(settings.dataDirectory.resolve("legacy-font")).cached())
        throw new FormatChanged("Legacy font generator available");
      if (zip.getEntry("tabprefix-hud.json") != null) {
        if (!version.bitmapFonts()) throw new IOException("Unsupported legacy canvas");
        hud = NativeHudLayout.read(readJson(zip, "tabprefix-hud.json"));
        for (NativeHudLayout.Profile p : hud.profiles.values())
          for (int n = 0; n < p.frames(); n++)
            if (!canvasCodes.contains(p.base + n)) throw new IOException("Missing canvas glyph");
        for (int n = 0; n < 26; n++)
          if (!canvasCodes.contains(NativeHudLayout.SPACE + n))
            throw new IOException("Missing canvas spacing");
      }
    }

    Set<UUID> assets = new HashSet<>();
    for (AssetDescriptor asset : snapshot.assets.values())
      if (asset.assigned()) {
        boolean all = true;
        for (int code : asset.glyphs()) all &= codes.contains(code);
        if (all) assets.add(asset.id);
      }
    return new PackRevision(hash, url(hash), file, assets, codes.size(), pages, format, time, hud);
  }

  private static boolean needsCanvas(DisplayDesign d) {
    return d != null
        && (d.sidebarEnabled && d.sidebarMode.equals("HUD")
            || d.screenEnabled
                && d.screen.stream().anyMatch(e -> e.enabled && e.anchor.equals("FREE_XY")));
  }

  private static JsonObject readJson(ZipFile zip, String name) throws IOException {
    ZipEntry entry = zip.getEntry(name);
    if (entry == null || entry.getSize() > 1048576) throw new IOException("Invalid pack metadata.");
    try (InputStream in = zip.getInputStream(entry);
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      return new JsonParser().parse(reader).getAsJsonObject();
    }
  }

  private void publish(PackRevision revision) throws IOException {
    JsonObject info = new JsonObject();
    info.addProperty("hash", revision.hash);
    info.addProperty("createdAt", revision.createdAt);
    AtomicFiles.write(
        settings.packsDirectory.resolve("active.json"),
        info.toString().getBytes(StandardCharsets.UTF_8));
  }

  private String url(String hash) {
    String base =
        settings.features.packBase.isEmpty() ? publicBase.get() : settings.features.packBase;
    return base
        + "/packs/"
        + (settings.features.versioned
            ? "TabPrefix-" + hash + ".zip"
            : "TabPrefix.zip?hash=" + hash);
  }

  private void prune(PackRevision current) {
    try {
      java.util.List<Path> packs = new ArrayList<>();
      try (DirectoryStream<Path> files =
          Files.newDirectoryStream(settings.packsDirectory, "TabPrefix-*.zip")) {
        for (Path p : files)
          if (p.getFileName().toString().matches("TabPrefix-[a-f0-9]{40}\\.zip")) packs.add(p);
      }
      packs.sort(
          Comparator.comparingLong(
                  (Path p) -> {
                    try {
                      return Files.getLastModifiedTime(p).toMillis();
                    } catch (IOException e) {
                      return 0;
                    }
                  })
              .reversed());
      int kept = 1;
      long now = System.currentTimeMillis();
      pins.entrySet().removeIf(e -> e.getValue() < now);
      for (Path p : packs) {
        String hash = p.getFileName().toString().substring(10, 50);
        if (hash.equals(current.hash) || pins.containsKey(hash)) continue;
        if (kept++ >= settings.features.keepPacks) Files.deleteIfExists(p);
      }
    } catch (IOException ignored) {
      /* Retention is best effort; an already published valid pack stays valid. */
    }
  }

  private static final class Glyph {
    final AssetDescriptor asset;
    final int frame;

    Glyph(AssetDescriptor asset, int frame) {
      this.asset = asset;
      this.frame = frame;
    }
  }

  private static final class FormatChanged extends IOException {
    FormatChanged(String message) {
      super(message);
    }
  }
}
