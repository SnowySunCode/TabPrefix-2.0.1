package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;

/** Bitmap canvas using padding and signed spacing, without overriding shaders or regular digits. */
public final class NativeHudFonts {
  private static final class Request {
    final String id;
    final int y, height;
    final AssetDescriptor image;

    Request(String id, int y, int height, AssetDescriptor image) {
      this.id = id;
      this.y = y;
      this.height = height;
      this.image = image;
    }
  }

  public static NativeHudLayout generate(
      Map<String, byte[]> entries,
      JsonArray providers,
      DisplayDesign d,
      GraphicSnapshot assets,
      MediaStore media,
      MinecraftVersion version)
      throws IOException {
    if (d == null || !version.bitmapFonts()) return NativeHudLayout.EMPTY;
    java.util.List<Request> requests = new ArrayList<>();
    if (d.screenEnabled)
      for (DisplayDesign.Screen s : d.screen)
        if (s.enabled && s.anchor.equals("FREE_XY")) {
          AssetDescriptor a = s.image == null ? null : assets.assets.get(s.image);
          if (s.kind.equals("TEXT") || a != null && a.assigned())
            requests.add(new Request("screen:" + s.id, s.y, s.height, a));
        }
    if (d.sidebarEnabled && d.sidebarMode.equals("HUD")) {
      requests.add(new Request("sidebar:title", d.sidebarY, 8, null));
      for (int n = 0; n < d.sidebar.size(); n++)
        requests.add(new Request("sidebar:" + n, d.sidebarY + 12 + n * 10, 8, null));
    }
    if (requests.isEmpty()) return NativeHudLayout.EMPTY;
    requests.add(new Request("action", 0, 8, null));
    spaces(entries, providers, version);
    Map<String, int[]> widthsBySize = new HashMap<>();
    Map<String, java.util.List<JsonObject>> fonts = new HashMap<>();
    Map<String, NativeHudLayout.Profile> profiles = new LinkedHashMap<>();
    int profile = 0;
    for (Request r : requests) {
      int base = NativeHudLayout.FIRST + (profile++) * NativeHudLayout.STRIDE;
      int[] widths;
      int logicalHeight = Math.max(40, 8 - r.y),
          imageWidth =
              r.image == null
                  ? 0
                  : Math.min(
                      510,
                      Math.max(
                          1, (int) Math.round((double) r.image.width * r.height / r.image.height)));
      int scale = 1;
      while ((logicalHeight + scale - 1) / scale > 256 || (imageWidth + scale - 1) / scale > 256) {
        scale++;
        while (r.height % scale != 0) scale++;
      }
      int cellHeight = (logicalHeight + scale - 1) / scale, renderHeight = cellHeight * scale;
      String key = r.height + ":" + cellHeight + ":" + scale;
      if (r.image == null) {
        if (!fonts.containsKey(key)) {
          java.util.List<JsonObject> fs = new ArrayList<>();
          widthsBySize.put(key, text(entries, fs, r.height, cellHeight, scale));
          fonts.put(key, fs);
        }
        widths = widthsBySize.get(key);
        for (JsonObject original : fonts.get(key)) {
          JsonObject p = new JsonParser().parse(original.toString()).getAsJsonObject();
          int start = p.remove("start").getAsInt(), count = p.remove("count").getAsInt();
          p.addProperty("ascent", 7 - r.y);
          p.add("chars", rows(base + start, count, 16));
          providers.add(p);
        }
      } else {
        if (r.image.frames() > NativeHudLayout.STRIDE)
          throw new IOException("Too many canvas frames");
        widths = new int[r.image.frames()];
        for (int frame = 0; frame < widths.length; frame++) {
          BufferedImage source = media.frame(r.image, frame);
          int w =
              Math.min(
                  510,
                  Math.max(
                      1,
                      (int)
                          Math.round((double) source.getWidth() * r.height / source.getHeight())));
          int pixelWidth = Math.max(1, w / scale);
          BufferedImage tile =
              new BufferedImage(pixelWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
          Graphics2D g = tile.createGraphics();
          try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, 0, 0, pixelWidth, r.height / scale, null);
          } finally {
            g.dispose();
          }
          widths[frame] = advance(tile, 0, 0, pixelWidth, cellHeight, scale);
          String file = "hud-image-" + (profile - 1) + "-" + frame + ".png";
          png(entries, file, tile);
          JsonObject p = provider(file, renderHeight);
          p.addProperty("ascent", 7 - r.y);
          p.add("chars", rows(base + frame, 1, 1));
          providers.add(p);
        }
      }
      profiles.put(
          r.id,
          new NativeHudLayout.Profile(
              r.id, r.image == null ? "" : r.image.id.toString(), base, r.y, r.height, widths));
    }
    return new NativeHudLayout(profiles);
  }

  private static int[] text(
      Map<String, byte[]> entries,
      java.util.List<JsonObject> fonts,
      int size,
      int cellHeight,
      int scale)
      throws IOException {
    String chars = NativeHudLayout.ALPHABET;
    int[] widths = new int[chars.length()];
    final int fontSize = size / scale, cw = Math.max(4, fontSize + 4), cols = 16, capacity = 64;
    for (int start = 0; start < chars.length(); start += capacity) {
      int count = Math.min(capacity, chars.length() - start), rowCount = (count + cols - 1) / cols;
      BufferedImage image =
          new BufferedImage(cols * cw, rowCount * cellHeight, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = image.createGraphics();
      try {
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
        g.setColor(Color.WHITE);
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        for (int n = 0; n < count; n++)
          g.drawString(
              String.valueOf(chars.charAt(start + n)),
              n % cols * cw,
              n / cols * cellHeight + fontSize - 1);
      } finally {
        g.dispose();
      }
      for (int n = 0; n < count; n++)
        widths[start + n] =
            advance(image, n % cols * cw, n / cols * cellHeight, cw, cellHeight, scale);
      String file = "hud-text-" + size + "-" + cellHeight + "-" + scale + "-" + start + ".png";
      png(entries, file, image);
      JsonObject p = provider(file, cellHeight * scale);
      p.addProperty("start", start);
      p.addProperty("count", count);
      fonts.add(p);
    }
    return widths;
  }

  private static int advance(BufferedImage image, int x, int y, int width, int height, int scale) {
    for (int col = width - 1; col >= 0; col--)
      for (int row = 0; row < height; row++)
        if ((image.getRGB(x + col, y + row) >>> 24) != 0) return (col + 1) * scale + 1;
    return 1;
  }

  private static JsonObject provider(String file, int height) {
    JsonObject j = new JsonObject();
    j.addProperty("type", "bitmap");
    j.addProperty("file", "tabprefix:font/" + file);
    j.addProperty("height", height);
    return j;
  }

  private static JsonArray rows(int base, int count, int cols) {
    JsonArray a = new JsonArray();
    for (int n = 0; n < count; n += cols) {
      StringBuilder s = new StringBuilder();
      for (int col = 0; col < cols; col++) s.appendCodePoint(n + col < count ? base + n + col : 0);
      a.add(s.toString());
    }
    return a;
  }

  private static void png(Map<String, byte[]> entries, String file, BufferedImage image)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", out)) throw new IOException("PNG writer unavailable");
    entries.put("assets/tabprefix/textures/font/" + file, out.toByteArray());
  }

  private static void spaces(
      Map<String, byte[]> entries, JsonArray providers, MinecraftVersion version)
      throws IOException {
    boolean modern = version.compareTo(MinecraftVersion.parse("1.19")) >= 0;
    JsonObject widths = new JsonObject();
    if (!modern) {
      BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
      pixel.setRGB(0, 0, 0xffffffff);
      png(entries, "hud-space.png", pixel);
    }
    for (int sign = 0; sign < 2; sign++)
      for (int bit = 0; bit < 13; bit++) {
        int width = (1 << bit) * (sign == 0 ? 1 : -1);
        String c = new String(Character.toChars(NativeHudLayout.SPACE + sign * 13 + bit));
        if (modern) widths.addProperty(c, width);
        else {
          JsonObject p = provider("hud-space.png", width > 0 ? width - 1 : width - 2);
          p.addProperty("ascent", -8192);
          JsonArray a = new JsonArray();
          a.add(c);
          p.add("chars", a);
          providers.add(p);
        }
      }
    if (modern) {
      JsonObject p = new JsonObject();
      p.addProperty("type", "space");
      p.add("advances", widths);
      providers.add(p);
    }
  }

  private NativeHudFonts() {}
}
