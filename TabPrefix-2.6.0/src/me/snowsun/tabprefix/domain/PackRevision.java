package me.snowsun.tabprefix.domain;

import java.nio.file.Path;
import java.util.*;

public final class PackRevision {
  public final String hash, fileName, url;
  public final Path file;
  public final Set<UUID> assets;
  public final int glyphs, atlases, packFormat;
  public final long createdAt;
  public final NativeHudLayout hud;

  public PackRevision(
      String hash,
      String url,
      Path file,
      Set<UUID> assets,
      int glyphs,
      int atlases,
      int format,
      long time) {
    this(hash, url, file, assets, glyphs, atlases, format, time, NativeHudLayout.EMPTY);
  }

  public PackRevision(
      String hash,
      String url,
      Path file,
      Set<UUID> assets,
      int glyphs,
      int atlases,
      int format,
      long time,
      NativeHudLayout hud) {
    this.hud = java.util.Objects.requireNonNull(hud);
    if (!hash.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid pack hash.");
    this.hash = hash;
    this.url = url;
    this.file = file;
    fileName = file.getFileName().toString();
    this.assets = Collections.unmodifiableSet(new HashSet<>(assets));
    this.glyphs = glyphs;
    this.atlases = atlases;
    packFormat = format;
    createdAt = time;
  }
}
