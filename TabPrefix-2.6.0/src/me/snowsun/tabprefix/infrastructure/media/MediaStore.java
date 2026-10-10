package me.snowsun.tabprefix.infrastructure.media;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.util.AtomicFiles;

public final class MediaStore {
  private final Path root, temp;
  private volatile PluginSettings settings;

  public MediaStore(PluginSettings s) throws IOException {
    settings = s;
    root = s.dataDirectory.resolve("assets");
    temp = s.tempDirectory.resolve("media");
    Files.createDirectories(root);
    Files.createDirectories(temp);
  }

  public void reload(PluginSettings s) {
    settings = s;
  }

  public synchronized AssetDescriptor store(
      byte[] source, ProcessedMedia media, EditorOptions options) throws IOException {
    UUID stagingId = UUID.randomUUID();
    Path staging = temp.resolve(stagingId.toString());
    Files.createDirectory(staging);
    try {
      Files.write(staging.resolve("source." + media.format), source);
      Files.write(
          staging.resolve("options.json"),
          new com.google.gson.Gson()
              .toJson(options)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      for (int i = 0; i < media.frames.size(); i++) {
        if (!ImageIO.write(media.frames.get(i), "png", staging.resolve(frameName(i)).toFile()))
          throw new IOException("PNG writer is unavailable.");
      }
      java.security.MessageDigest digest;
      try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
      java.util.List<Path> files = new ArrayList<>();
      try (java.util.stream.Stream<Path> paths = Files.list(staging)) {
        paths.sorted().forEach(files::add);
      }
      for (Path file : files) {
        byte[] content = Files.readAllBytes(file);
        digest.update(
            file.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(content.length).array());
        digest.update(content);
      }
      for (int delay : media.delays)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(delay).array());
      java.nio.ByteBuffer hash = java.nio.ByteBuffer.wrap(digest.digest());
      UUID id =
          new UUID(
              (hash.getLong() & 0xffffffffffff0fffL) | 0x5000L,
              (hash.getLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
      Path target = root.resolve(id.toString());
      boolean duplicate = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
      if (!duplicate && bytes(root) + bytes(staging) > settings.features.storageBytes)
        throw new IllegalArgumentException("Image storage quota reached.");
      if (!duplicate) AtomicFiles.move(staging, target);
      return new AssetDescriptor(
          id,
          media.format,
          settings.features.cellWidth,
          settings.features.cellHeight,
          options.renderHeight,
          options.ascent,
          media.delays,
          new int[0],
          System.currentTimeMillis());
    } finally {
      AtomicFiles.deleteTree(staging);
    }
  }

  public BufferedImage frame(AssetDescriptor asset, int index) throws IOException {
    if (index < 0 || index >= asset.frames())
      throw new IllegalArgumentException("Invalid frame index.");
    Path path = root.resolve(asset.id.toString()).resolve(frameName(index));
    try (InputStream stream = Files.newInputStream(path);
        MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(stream)) {
      Iterator<ImageReader> it = ImageIO.getImageReaders(input);
      if (!it.hasNext()) throw new IOException("Invalid stored frame.");
      ImageReader reader = it.next();
      try {
        reader.setInput(input);
        if (reader.getWidth(0) != asset.width || reader.getHeight(0) != asset.height)
          throw new IOException("Stored frame dimensions mismatch.");
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  public byte[] source(AssetDescriptor asset) throws IOException {
    Path file = root.resolve(asset.id.toString()).resolve("source." + asset.sourceFormat);
    if (Files.size(file) > settings.features.uploadBytes)
      throw new IOException("Original exceeds current upload limit.");
    return Files.readAllBytes(file);
  }

  public com.google.gson.JsonObject options(AssetDescriptor asset) throws IOException {
    Path file = root.resolve(asset.id.toString()).resolve("options.json");
    if (!Files.exists(file)) return new com.google.gson.JsonObject();
    if (Files.size(file) > 4096) throw new IOException("Invalid saved image options.");
    return new com.google.gson.JsonParser()
        .parse(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8))
        .getAsJsonObject();
  }

  public void verify(GraphicSnapshot snapshot) throws IOException {
    for (AssetDescriptor asset : snapshot.assets.values())
      if (asset.assigned()) for (int i = 0; i < asset.frames(); i++) frame(asset, i);
  }

  public synchronized void delete(UUID id) throws IOException {
    AtomicFiles.deleteTree(root.resolve(id.toString()));
  }

  public synchronized void cleanup(Set<UUID> known, long now) throws IOException {
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
      for (Path path : dirs) {
        UUID id;
        try {
          id = UUID.fromString(path.getFileName().toString());
        } catch (IllegalArgumentException e) {
          continue;
        }
        if (!known.contains(id)
            && Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                < now - 3600000L) AtomicFiles.deleteTree(path);
      }
    }
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(temp)) {
      for (Path path : dirs)
        if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() < now - 3600000L)
          AtomicFiles.deleteTree(path);
    }
  }

  private static String frameName(int index) {
    return String.format(Locale.ROOT, "frame-%04d.png", index);
  }

  private static long bytes(Path folder) throws IOException {
    try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
      Iterator<Path> it =
          files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
      long total = 0;
      while (it.hasNext()) total += Files.size(it.next());
      return total;
    }
  }
}
