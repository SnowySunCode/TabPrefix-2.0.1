package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;
import me.snowsun.tabprefix.util.*;

/** Deterministic ZIP generation. Old glyph assignments remain in every new pack. */
public final class PackBuilder {
  private final MediaStore media;
  private final MinecraftVersion version;
  private final String publicBase;
  private final Map<String, Long> pins = new ConcurrentHashMap<>();
  private volatile PluginSettings settings;

  public PackBuilder(
      PluginSettings settings, MediaStore media, MinecraftVersion version, String publicBase) {
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
      if (a.assigned()) {
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
    JsonObject font = new JsonObject();
    font.add("providers", providers);
    byte[] json = font.toString().getBytes(StandardCharsets.UTF_8);
    entries.put("assets/minecraft/font/default.json", json);
    entries.put("assets/tabprefix/font/prefix.json", json);
    int format = version.compareTo(MinecraftVersion.parse("1.16.2")) < 0 ? 5 : 6;
    JsonObject pack = new JsonObject(), meta = new JsonObject();
    pack.addProperty("pack_format", format);
    pack.addProperty("description", f.description);
    meta.add("pack", pack);
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
              hash, url(hash), target, included, count, pages, format, System.currentTimeMillis());
      publish(revision);
      prune(revision);
      return revision;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public PackRevision load(GraphicSnapshot snapshot) throws IOException {
    Path path = settings.packsDirectory.resolve("active.json");
    if (!Files.exists(path)) return null;
    if (Files.size(path) > 65536) throw new IOException("Invalid active pack metadata.");
    JsonObject info =
        new JsonParser()
            .parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
            .getAsJsonObject();
    return inspect(info.get("hash").getAsString(), snapshot, info.get("createdAt").getAsLong());
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
    Set<Integer> codes = new HashSet<>();
    int format, pages = 0;
    try (ZipFile zip = new ZipFile(file.toFile())) {
      JsonObject metadata = readJson(zip, "pack.mcmeta");
      format = metadata.getAsJsonObject("pack").get("pack_format").getAsInt();
      int expected = version.compareTo(MinecraftVersion.parse("1.16.2")) < 0 ? 5 : 6;
      if (format != expected)
        throw new IOException("Pack format differs from this Minecraft version.");
      JsonArray providers =
          readJson(zip, "assets/minecraft/font/default.json").getAsJsonArray("providers");
      for (JsonElement element : providers) {
        JsonObject provider = element.getAsJsonObject();
        String texture = provider.get("file").getAsString();
        if (!texture.startsWith("tabprefix:font/")
            || zip.getEntry("assets/tabprefix/textures/" + texture.substring("tabprefix:".length()))
                == null) throw new IOException("Missing pack texture.");
        pages++;
        for (JsonElement row : provider.getAsJsonArray("chars"))
          for (char c : row.getAsString().toCharArray()) if (c != 0) codes.add((int) c);
      }
    }
    Set<UUID> assets = new HashSet<>();
    for (AssetDescriptor asset : snapshot.assets.values())
      if (asset.assigned()) {
        boolean all = true;
        for (int code : asset.glyphs()) all &= codes.contains(code);
        if (all) assets.add(asset.id);
      }
    return new PackRevision(hash, url(hash), file, assets, codes.size(), pages, format, time);
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
    String base = settings.features.packBase.isEmpty() ? publicBase : settings.features.packBase;
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
}
