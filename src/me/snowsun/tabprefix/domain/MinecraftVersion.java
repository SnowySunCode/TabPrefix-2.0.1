package me.snowsun.tabprefix.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MinecraftVersion implements Comparable<MinecraftVersion> {
  private static final Pattern VERSION =
      Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-+ ].*)?$");
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
    return major == 1 && minor == 16 && patch >= 0 && patch <= 5;
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
