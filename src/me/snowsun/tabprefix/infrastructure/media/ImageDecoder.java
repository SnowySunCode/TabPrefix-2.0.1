package me.snowsun.tabprefix.infrastructure.media;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageInputStream;
import me.snowsun.tabprefix.config.FeatureSettings;
import me.snowsun.tabprefix.domain.EditorOptions;
import org.w3c.dom.Node;

/** Detects content, validates dimensions before decoding, and composites GIF disposal correctly. */
public final class ImageDecoder {
  public ProcessedMedia decode(byte[] bytes, EditorOptions options, FeatureSettings f)
      throws IOException {
    if (!f.images) throw new IllegalArgumentException("Images are disabled.");
    if (bytes.length == 0 || bytes.length > f.uploadBytes)
      throw new IllegalArgumentException("Image exceeds upload limit.");
    try (MemoryCacheImageInputStream input =
        new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IllegalArgumentException("Unsupported or invalid image.");
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, false, false);
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (format.equals("jpeg")) format = "jpg";
        if (!f.formats.contains(format))
          throw new IllegalArgumentException("Image format is disabled: " + format);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        checkDimensions(width, height, f);
        if (!format.equals("gif")) {
          if ((long) width * height > f.decodedPixels)
            throw new IllegalArgumentException("Decoded image exceeds pixel budget.");
          BufferedImage raw = reader.read(0);
          if (format.equals("jpg")) raw = ImageTransform.exif(raw, orientation(bytes));
          return new ProcessedMedia(
              format,
              raw.getWidth(),
              raw.getHeight(),
              Collections.singletonList(ImageTransform.transform(raw, options, f)),
              new int[] {Math.max(1, (f.defaultDelay + 49) / 50)});
        }
        Node stream = tree(reader.getStreamMetadata(), "javax_imageio_gif_stream_1.0");
        Node screen = child(stream, "LogicalScreenDescriptor");
        if (screen != null) {
          width = attribute(screen, "logicalScreenWidth", width);
          height = attribute(screen, "logicalScreenHeight", height);
        }
        checkDimensions(width, height, f);
        int count = 0;
        while (count <= f.maxFrames) {
          try {
            reader.getWidth(count);
            count++;
          } catch (IndexOutOfBoundsException e) {
            break;
          }
        }
        if (count < 1 || count > f.maxFrames)
          throw new IllegalArgumentException("Animation contains too many frames.");
        if (!f.animation && count > 1)
          throw new IllegalArgumentException("Animated prefixes are disabled.");
        if ((long) width * height * (count + 2) > f.decodedPixels)
          throw new IllegalArgumentException("Animation exceeds decoded pixel budget.");
        Node colors = child(stream, "GlobalColorTable");
        int background = 0;
        if (colors != null) {
          int index = attribute(colors, "backgroundColorIndex", 0);
          for (Node entry = colors.getFirstChild(); entry != null; entry = entry.getNextSibling())
            if (attribute(entry, "index", -1) == index)
              background =
                  0xff000000
                      | attribute(entry, "red", 0) << 16
                      | attribute(entry, "green", 0) << 8
                      | attribute(entry, "blue", 0);
        }
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.util.List<BufferedImage> frames = new ArrayList<>();
        int[] delays = new int[count];
        for (int i = 0; i < count; i++) {
          Node metadata = tree(reader.getImageMetadata(i), "javax_imageio_gif_image_1.0"),
              descriptor = child(metadata, "ImageDescriptor"),
              control = child(metadata, "GraphicControlExtension");
          int left = attribute(descriptor, "imageLeftPosition", 0),
              top = attribute(descriptor, "imageTopPosition", 0),
              fw = reader.getWidth(i),
              fh = reader.getHeight(i);
          if (left < 0 || top < 0 || (long) left + fw > width || (long) top + fh > height)
            throw new IllegalArgumentException("GIF frame exceeds logical canvas.");
          boolean transparent = "TRUE".equals(value(control, "transparentColorFlag", "FALSE"));
          int clear = transparent ? 0 : background;
          if (i == 0) {
            Graphics2D g = canvas.createGraphics();
            try {
              g.setComposite(AlphaComposite.Src);
              g.setColor(new Color(clear, true));
              g.fillRect(0, 0, width, height);
            } finally {
              g.dispose();
            }
          }
          String disposal = value(control, "disposalMethod", "none");
          BufferedImage previous = "restoreToPrevious".equals(disposal) ? copy(canvas) : null;
          BufferedImage frame = reader.read(i);
          Graphics2D g = canvas.createGraphics();
          try {
            g.drawImage(frame, left, top, null);
          } finally {
            g.dispose();
          }
          frames.add(ImageTransform.transform(canvas, options, f));
          int ms = attribute(control, "delayTime", 0) * 10;
          if (ms <= 0) ms = f.defaultDelay;
          delays[i] = Math.max(1, (Math.max(ms, f.minDelay) + 49) / 50);
          if (previous != null) canvas = previous;
          else if ("restoreToBackgroundColor".equals(disposal)) {
            g = canvas.createGraphics();
            try {
              g.setComposite(AlphaComposite.Src);
              g.setColor(new Color(clear, true));
              g.fillRect(left, top, fw, fh);
            } finally {
              g.dispose();
            }
          }
        }
        return new ProcessedMedia(format, width, height, frames, delays);
      } finally {
        reader.dispose();
      }
    }
  }

  private static void checkDimensions(int width, int height, FeatureSettings f) {
    if (width < 1 || height < 1 || width > f.maxWidth || height > f.maxHeight)
      throw new IllegalArgumentException("Image dimensions exceed configured limits.");
  }

  private static BufferedImage copy(BufferedImage source) {
    BufferedImage out =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    try {
      g.drawImage(source, 0, 0, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  private static Node tree(IIOMetadata m, String name) {
    return m == null ? null : m.getAsTree(name);
  }

  private static Node child(Node root, String name) {
    if (root == null) return null;
    for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling())
      if (n.getNodeName().equals(name)) return n;
    return null;
  }

  private static String value(Node node, String key, String fallback) {
    Node attr =
        node == null || node.getAttributes() == null
            ? null
            : node.getAttributes().getNamedItem(key);
    return attr == null ? fallback : attr.getNodeValue();
  }

  private static int attribute(Node n, String k, int d) {
    try {
      return Integer.parseInt(value(n, k, String.valueOf(d)));
    } catch (NumberFormatException e) {
      return d;
    }
  }

  private static int orientation(byte[] b) {
    try {
      int p = 2;
      while (p + 4 <= b.length) {
        if ((b[p] & 255) != 255) break;
        int marker = b[p + 1] & 255;
        if (marker == 0xda || marker == 0xd9) break;
        int length = ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
        if (length < 2 || p + 2 + length > b.length) break;
        if (marker == 0xe1
            && length >= 16
            && b[p + 4] == 'E'
            && b[p + 5] == 'x'
            && b[p + 6] == 'i'
            && b[p + 7] == 'f') {
          int base = p + 10;
          boolean little = b[base] == 'I' && b[base + 1] == 'I';
          if (!little && !(b[base] == 'M' && b[base + 1] == 'M')) return 1;
          int offset = integer(b, base + 4, little);
          int ifd = base + offset, end = p + 2 + length;
          if (offset < 8 || ifd < base || ifd + 2 > end) return 1;
          int count = shortInt(b, ifd, little);
          for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) break;
            if (shortInt(b, entry, little) == 0x112
                && shortInt(b, entry + 2, little) == 3
                && integer(b, entry + 4, little) == 1) return shortInt(b, entry + 8, little);
          }
          return 1;
        }
        p += length + 2;
      }
    } catch (IndexOutOfBoundsException e) {
      return 1;
    }
    return 1;
  }

  private static int shortInt(byte[] b, int p, boolean le) {
    return le ? (b[p] & 255) | (b[p + 1] & 255) << 8 : (b[p] & 255) << 8 | (b[p + 1] & 255);
  }

  private static int integer(byte[] b, int p, boolean le) {
    return le
        ? shortInt(b, p, true) | (shortInt(b, p + 2, true) << 16)
        : (shortInt(b, p, false) << 16) | shortInt(b, p + 2, false);
  }
}
