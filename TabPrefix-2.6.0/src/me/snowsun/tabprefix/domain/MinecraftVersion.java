package me.snowsun.tabprefix.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MinecraftVersion implements Comparable<MinecraftVersion> {
  public static final String SUPPORTED = "1.12-1.21.11, 26.1-26.3.x";
  private static final int[] LAST_PATCH = {2, 2, 4, 2, 5, 1, 2, 4, 6, 11};
  private static final Pattern VERSION =
      Pattern.compile(
          "^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:\\.build\\.\\d+(?:-[a-zA-Z0-9.-]+)?|[-+ ].*)?$");
  public final int major;
  public final int minor;
  public final int patch;

  private MinecraftVersion(int major, int minor, int patch) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
  }

  public static MinecraftVersion parse(String input) {
    Matcher match = VERSION.matcher(input.trim());
    if (!match.matches()) throw new IllegalArgumentException("Invalid Minecraft version: " + input);
    return new MinecraftVersion(
        Integer.parseInt(match.group(1)),
        Integer.parseInt(match.group(2)),
        match.group(3) == null ? 0 : Integer.parseInt(match.group(3)));
  }

  public boolean supported() {
    if (major == 1 && minor >= 12 && minor <= 21)
      return patch >= 0 && patch <= LAST_PATCH[minor - 12];
    return major == 26 && minor >= 1 && minor <= 3 && patch >= 0 && patch <= 99;
  }

  public boolean rgb() {
    return compareTo(parse("1.16")) >= 0;
  }

  public boolean bitmapFonts() {
    return compareTo(parse("1.13")) >= 0;
  }

  public int teamTextLimit() {
    return compareTo(parse("1.13")) < 0 ? 16 : 64;
  }

  public int objectiveTitleLimit() {
    return compareTo(parse("1.13")) < 0 ? 32 : 128;
  }

  @Override
  public int compareTo(MinecraftVersion other) {
    int result = Integer.compare(major, other.major);
    if (result == 0) result = Integer.compare(minor, other.minor);
    return result == 0 ? Integer.compare(patch, other.patch) : result;
  }

  @Override
  public String toString() {
    return major + "." + minor + (patch == 0 ? "" : "." + patch);
  }
}
