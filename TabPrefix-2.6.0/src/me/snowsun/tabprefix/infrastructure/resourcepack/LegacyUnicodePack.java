package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.infrastructure.media.MediaStore;

/** Legacy font pages: 16x16 cells, 256x256 textures, one metric byte per BMP codepoint. */
public final class LegacyUnicodePack {
  public static int generate(
      Map<String, byte[]> entries,
      GraphicSnapshot snapshot,
      MediaStore media,
      LegacyFontAssets originals)
      throws IOException {
    Set<Integer> codes = new HashSet<>();
    java.util.List<AssetDescriptor> assets = new ArrayList<>();
    for (AssetDescriptor a : snapshot.assets.values())
      if (a.assigned()) {
        assets.add(a);
        for (int c : a.glyphs()) codes.add(c);
      }
    if (codes.isEmpty()) return 0;
    assets.sort(Comparator.comparingInt(a -> a.glyphCode(0)));
    byte[] widths = originals.widths();
    Map<Integer, BufferedImage> pages = new TreeMap<>();
    for (int c : codes) {
      int p = c >> 8;
      if (pages.containsKey(p)) continue;
      BufferedImage page = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
      boolean preserve = false;
      for (int n = p << 8; n < (p + 1) << 8; n++)
        if (widths[n] != 0 && !codes.contains(n)) {
          preserve = true;
          break;
        }
      if (preserve) {
        BufferedImage vanilla = ImageIO.read(new ByteArrayInputStream(originals.page(p)));
        if (vanilla == null || vanilla.getWidth() != 256 || vanilla.getHeight() != 256)
          throw new IOException("Invalid original Unicode page");
        Graphics2D g = page.createGraphics();
        try {
          g.drawImage(vanilla, 0, 0, null);
        } finally {
          g.dispose();
        }
      }
      pages.put(p, page);
    }
    JsonArray mappings = new JsonArray();
    for (AssetDescriptor a : assets)
      for (int frame = 0; frame < a.frames(); frame++) {
        int code = a.glyphCode(frame), x = (code & 15) * 16, y = ((code >> 4) & 15) * 16;
        BufferedImage image = media.frame(a, frame);
        Graphics2D g = pages.get(code >> 8).createGraphics();
        try {
          g.setComposite(AlphaComposite.Clear);
          g.fillRect(x, y, 16, 16);
          g.setComposite(AlphaComposite.Src);
          g.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION,
              RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
          double scale = Math.min(16d / image.getWidth(), 16d / image.getHeight());
          int w = Math.max(1, (int) Math.round(image.getWidth() * scale)),
              h = Math.max(1, (int) Math.round(image.getHeight() * scale));
          g.drawImage(image, x + (16 - w) / 2, y + (16 - h) / 2, w, h, null);
        } finally {
          g.dispose();
        }
        widths[code] = 0x0f;
        JsonObject j = new JsonObject();
        j.addProperty("asset", a.id.toString());
        j.addProperty("frame", frame);
        j.addProperty("codepoint", code);
        mappings.add(j);
      }
    for (Map.Entry<Integer, BufferedImage> e : pages.entrySet()) {
      ByteArrayOutputStream png = new ByteArrayOutputStream();
      ImageIO.write(e.getValue(), "png", png);
      entries.put(
          String.format(
              Locale.ROOT, "assets/minecraft/textures/font/unicode_page_%02x.png", e.getKey()),
          png.toByteArray());
    }
    entries.put("assets/minecraft/font/glyph_sizes.bin", widths);
    JsonObject index = new JsonObject();
    index.addProperty("schema", 1);
    index.addProperty("renderer", "legacy-unicode");
    index.addProperty("cell", 16);
    index.add("glyphs", mappings);
    entries.put("tabprefix-glyphs.json", index.toString().getBytes(StandardCharsets.UTF_8));
    return pages.size();
  }
}
