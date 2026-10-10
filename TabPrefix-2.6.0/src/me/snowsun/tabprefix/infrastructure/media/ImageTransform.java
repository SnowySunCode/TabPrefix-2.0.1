package me.snowsun.tabprefix.infrastructure.media;

import java.awt.*;
import java.awt.image.BufferedImage;
import me.snowsun.tabprefix.config.FeatureSettings;
import me.snowsun.tabprefix.domain.EditorOptions;

public final class ImageTransform {
  private ImageTransform() {}

  public static BufferedImage transform(BufferedImage source, EditorOptions o, FeatureSettings f) {
    int w = o.width == 0 ? source.getWidth() - o.x : o.width,
        h = o.height == 0 ? source.getHeight() - o.y : o.height;
    if (w < 1 || h < 1 || (long) o.x + w > source.getWidth() || (long) o.y + h > source.getHeight())
      throw new IllegalArgumentException("Crop is outside the source image.");
    BufferedImage crop = source.getSubimage(o.x, o.y, w, h), oriented = crop;
    if (o.rotation != 0 || o.flipX || o.flipY) {
      int rw = o.rotation % 180 == 0 ? w : h, rh = o.rotation % 180 == 0 ? h : w;
      oriented = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          int a = o.flipX ? w - 1 - x : x, b = o.flipY ? h - 1 - y : y, dx = a, dy = b;
          switch (o.rotation) {
            case 90:
              dx = h - 1 - b;
              dy = a;
              break;
            case 180:
              dx = w - 1 - a;
              dy = h - 1 - b;
              break;
            case 270:
              dx = b;
              dy = w - 1 - a;
              break;
            default:
              break;
          }
          oriented.setRGB(dx, dy, crop.getRGB(x, y));
        }
    }
    double sx = (double) f.cellWidth / oriented.getWidth(),
        sy = (double) f.cellHeight / oriented.getHeight();
    if (o.fit != EditorOptions.Fit.STRETCH) {
      double scale = o.fit == EditorOptions.Fit.COVER ? Math.max(sx, sy) : Math.min(sx, sy);
      sx = sy = scale;
    }
    double limit = o.upscale && f.upscale ? f.maxScale : 1;
    sx = Math.min(sx, limit);
    sy = Math.min(sy, limit);
    int dw = Math.max(1, (int) Math.round(oriented.getWidth() * sx)),
        dh = Math.max(1, (int) Math.round(oriented.getHeight() * sy));
    BufferedImage out = new BufferedImage(f.cellWidth, f.cellHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    try {
      if (!f.alpha) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
      }
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION,
          o.pixelArt
              ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
              : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.drawImage(oriented, (f.cellWidth - dw) / 2, (f.cellHeight - dh) / 2, dw, dh, null);
    } finally {
      g.dispose();
    }
    if (o.brightness != 0 || o.contrast != 1 || o.saturation != 1)
      for (int y = 0; y < out.getHeight(); y++)
        for (int x = 0; x < out.getWidth(); x++) {
          int argb = out.getRGB(x, y),
              r = (argb >> 16) & 255,
              green = (argb >> 8) & 255,
              b = argb & 255;
          double gray = .2126 * r + .7152 * green + .0722 * b;
          r = adjust(gray + (r - gray) * o.saturation, o);
          green = adjust(gray + (green - gray) * o.saturation, o);
          b = adjust(gray + (b - gray) * o.saturation, o);
          out.setRGB(x, y, (argb & 0xff000000) | (r << 16) | (green << 8) | b);
        }
    return out;
  }

  private static int adjust(double color, EditorOptions o) {
    return (int)
        Math.max(
            0,
            Math.min(255, Math.round((color - 127.5) * o.contrast + 127.5 + o.brightness * 255)));
  }

  static BufferedImage exif(BufferedImage in, int orientation) {
    if (orientation < 2 || orientation > 8) return in;
    int w = in.getWidth(), h = in.getHeight();
    boolean swap = orientation >= 5;
    BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int a = x, b = y;
        switch (orientation) {
          case 2:
            a = w - 1 - x;
            break;
          case 3:
            a = w - 1 - x;
            b = h - 1 - y;
            break;
          case 4:
            b = h - 1 - y;
            break;
          case 5:
            a = y;
            b = x;
            break;
          case 6:
            a = h - 1 - y;
            b = x;
            break;
          case 7:
            a = h - 1 - y;
            b = w - 1 - x;
            break;
          case 8:
            a = y;
            b = w - 1 - x;
            break;
          default:
            break;
        }
        out.setRGB(a, b, in.getRGB(x, y));
      }
    return out;
  }
}
