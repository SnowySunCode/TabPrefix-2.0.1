package me.snowsun.tabprefix.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Digests {
  private Digests() {}

  public static String hex(String algorithm, byte[] bytes) {
    try {
      byte[] hash = MessageDigest.getInstance(algorithm).digest(bytes);
      StringBuilder out = new StringBuilder();
      for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256(String value) {
    return hex("SHA-256", value.getBytes(StandardCharsets.UTF_8));
  }

  public static byte[] unhex(String value) {
    byte[] b = new byte[value.length() / 2];
    for (int i = 0; i < b.length; i++)
      b[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
    return b;
  }
}
