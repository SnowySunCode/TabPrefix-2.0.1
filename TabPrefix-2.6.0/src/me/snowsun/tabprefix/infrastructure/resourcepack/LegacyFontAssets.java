package me.snowsun.tabprefix.infrastructure.resourcepack;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import me.snowsun.tabprefix.util.*;

/** Fetch original 1.12 fonts from the hash-verified Mojang client, cache only font resources. */
public final class LegacyFontAssets {
  public static final class Unavailable extends IOException {
    public Unavailable(IOException e) {
      super(e.getMessage(), e);
    }
  }

  private final Path root;

  public LegacyFontAssets(Path root) {
    this.root = root;
  }

  public boolean cached() {
    try {
      return Files.isRegularFile(root.resolve("glyph_sizes.bin"), LinkOption.NOFOLLOW_LINKS)
          && Files.size(root.resolve("glyph_sizes.bin")) == 65536;
    } catch (IOException e) {
      return false;
    }
  }

  public synchronized byte[] widths() throws IOException {
    if (!cached())
      try {
        fetchFonts();
      } catch (IOException e) {
        throw new Unavailable(e);
      }
    byte[] result = Files.readAllBytes(root.resolve("glyph_sizes.bin"));
    if (result.length != 65536) throw new IOException("Expected original 65536-byte font metrics");
    return result;
  }

  public synchronized byte[] page(int number) throws IOException {
    String name = String.format(Locale.ROOT, "unicode_page_%02x.png", number);
    Path file = root.resolve(name);
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      try {
        fetchFonts();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Original font page missing: " + name);
      } catch (IOException e) {
        throw new Unavailable(e);
      }
    if (Files.size(file) > 1048576) throw new IOException("Font page too large");
    return Files.readAllBytes(file);
  }

  public synchronized void importWidths(byte[] data) throws IOException {
    if (data.length != 65536)
      throw new IllegalArgumentException("Expected original glyph_sizes.bin: 65536 bytes");
    store(root.resolve("glyph_sizes.bin"), data);
  }

  private void fetchFonts() throws IOException {
    try {
      JsonObject version = null;
      for (JsonElement e :
          json(download("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", 4000000))
              .getAsJsonArray("versions"))
        if (e.getAsJsonObject().get("id").getAsString().equals("1.12.2")) {
          version = e.getAsJsonObject();
          break;
        }
      if (version == null) throw new IOException("Mojang manifest missing 1.12.2");
      byte[] meta = download(version.get("url").getAsString(), 1048576);
      if (version.has("sha1")) verify(meta, version.get("sha1").getAsString());
      JsonObject client = json(meta).getAsJsonObject("downloads").getAsJsonObject("client");
      byte[] jar = download(client.get("url").getAsString(), 33554432);
      verify(jar, client.get("sha1").getAsString());
      extractFonts(jar);
    } catch (RuntimeException e) {
      throw new IOException("Invalid Mojang font metadata", e);
    }
  }

  public synchronized void extractFonts(byte[] jar) throws IOException {
    Map<String, byte[]> fonts = new TreeMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
      for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
        String name = e.getName(), target;
        int limit;
        if (name.equals("assets/minecraft/font/glyph_sizes.bin")) {
          target = "glyph_sizes.bin";
          limit = 65536;
        } else if (name.matches("assets/minecraft/textures/font/unicode_page_[0-9a-f]{2}\\.png")) {
          target = name.substring(name.lastIndexOf('/') + 1);
          limit = 1048576;
        } else continue;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = zip.read(buffer)) != -1; ) {
          if (out.size() + n > limit) throw new IOException("Font entry too large");
          out.write(buffer, 0, n);
        }
        if (fonts.put(target, out.toByteArray()) != null)
          throw new IOException("Duplicate font entry");
      }
    }
    if (!fonts.containsKey("glyph_sizes.bin") || fonts.get("glyph_sizes.bin").length != 65536)
      throw new IOException("Client missing original glyph_sizes.bin");
    for (Map.Entry<String, byte[]> e : fonts.entrySet()) {
      Path f = root.resolve(e.getKey());
      if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)
          || e.getKey().equals("glyph_sizes.bin") && !cached()) store(f, e.getValue());
    }
  }

  private static JsonObject json(byte[] bytes) throws IOException {
    try {
      return new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (RuntimeException e) {
      throw new IOException("Invalid Minecraft metadata", e);
    }
  }

  private static void verify(byte[] data, String hash) throws IOException {
    if (!hash.equals(Digests.hex("SHA-1", data)))
      throw new IOException("Minecraft asset hash mismatch");
  }

  private static byte[] download(String address, int limit) throws IOException {
    URL url = new URL(address);
    if (!url.getProtocol().equals("https")
        || !Arrays.asList(
                "piston-meta.mojang.com",
                "launchermeta.mojang.com",
                "piston-data.mojang.com",
                "launcher.mojang.com")
            .contains(url.getHost())) throw new IOException("Unexpected Minecraft asset host");
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(8000);
    c.setReadTimeout(12000);
    c.setRequestProperty("User-Agent", "TabPrefix/2.5");
    try {
      if (c.getResponseCode() != 200)
        throw new IOException(
            "Mojang unavailable; import original glyph_sizes.bin in Glyph Studio");
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) != -1; ) {
          if (out.size() + n > limit) throw new IOException("Minecraft resource too large");
          out.write(b, 0, n);
        }
        return out.toByteArray();
      }
    } finally {
      c.disconnect();
    }
  }

  private static void store(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = Files.createTempFile(file.getParent(), "font-", ".tmp");
    try {
      Files.write(tmp, bytes);
      AtomicFiles.move(tmp, file);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
