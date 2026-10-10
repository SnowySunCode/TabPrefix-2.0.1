package me.snowsun.tabprefix.domain;

public final class EditorOptions {
  public enum Fit {
    CONTAIN,
    COVER,
    STRETCH
  }

  public final int x, y, width, height, rotation, renderHeight, ascent;
  public final boolean pixelArt, upscale, flipX, flipY;
  public final Fit fit;
  public final double brightness, contrast, saturation;

  public EditorOptions(
      int x,
      int y,
      int w,
      int h,
      Fit fit,
      boolean pixel,
      boolean upscale,
      boolean flipX,
      boolean flipY,
      int rotation,
      int renderHeight,
      int ascent,
      double brightness,
      double contrast,
      double saturation) {
    if (x < 0
        || y < 0
        || w < 0
        || h < 0
        || rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270
        || renderHeight < 1
        || renderHeight > 64
        || ascent > renderHeight
        || ascent < -64
        || !Double.isFinite(brightness)
        || brightness < -1
        || brightness > 1
        || !Double.isFinite(contrast)
        || contrast < 0
        || contrast > 3
        || !Double.isFinite(saturation)
        || saturation < 0
        || saturation > 3) throw new IllegalArgumentException("Invalid editor options.");
    this.x = x;
    this.y = y;
    width = w;
    height = h;
    this.fit = java.util.Objects.requireNonNull(fit);
    pixelArt = pixel;
    this.upscale = upscale;
    this.flipX = flipX;
    this.flipY = flipY;
    this.rotation = rotation;
    this.renderHeight = renderHeight;
    this.ascent = ascent;
    this.brightness = brightness;
    this.contrast = contrast;
    this.saturation = saturation;
  }

  public static EditorOptions defaults(int renderHeight, int ascent) {
    return new EditorOptions(
        0, 0, 0, 0, Fit.CONTAIN, true, false, false, false, 0, renderHeight, ascent, 0, 1, 1);
  }
}
