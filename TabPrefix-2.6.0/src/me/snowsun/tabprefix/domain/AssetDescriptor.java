package me.snowsun.tabprefix.domain;

import java.util.UUID;

/** Immutable processed image metadata; glyphs are allocated permanently at application. */
public final class AssetDescriptor {
  public final UUID id;
  public final String sourceFormat;
  public final int width, height, renderHeight, ascent;
  public final long createdAt;
  private final int[] delays, glyphs;

  public AssetDescriptor(
      UUID id,
      String format,
      int width,
      int height,
      int renderHeight,
      int ascent,
      int[] delays,
      int[] glyphs,
      long createdAt) {
    if (id == null
        || delays.length < 1
        || width < 1
        || height < 1
        || renderHeight < 1
        || ascent > renderHeight
        || glyphs.length != 0 && glyphs.length != delays.length)
      throw new IllegalArgumentException("Invalid image metadata.");
    for (int delay : delays)
      if (delay < 1) throw new IllegalArgumentException("Frame delay must be positive.");
    for (int code : glyphs)
      if (code < 0xe000 || code > 0xf8ff)
        throw new IllegalArgumentException("Glyph outside the private Unicode range.");
    this.id = id;
    sourceFormat = format;
    this.width = width;
    this.height = height;
    this.renderHeight = renderHeight;
    this.ascent = ascent;
    this.delays = delays.clone();
    this.glyphs = glyphs.clone();
    this.createdAt = createdAt;
  }

  public int frames() {
    return delays.length;
  }

  public int[] delays() {
    return delays.clone();
  }

  public int[] glyphs() {
    return glyphs.clone();
  }

  public boolean assigned() {
    return glyphs.length > 0;
  }

  public int glyphCode(int frame) {
    return glyphs[frame];
  }

  public String glyph(long tick) {
    return assigned() ? String.valueOf((char) glyphs[frameAt(tick)]) : "";
  }

  public int frameAt(long tick) {
    long total = 0;
    for (int d : delays) total += d;
    long position = Math.floorMod(tick, total);
    for (int i = 0; i < delays.length; i++) {
      if (position < delays[i]) return i;
      position -= delays[i];
    }
    return 0;
  }
}
