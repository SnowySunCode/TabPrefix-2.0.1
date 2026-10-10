package me.snowsun.tabprefix.domain;

import com.google.gson.*;

/** Released Java Edition resource-pack formats, including the new major/minor metadata. */
public final class ResourcePackFormat {
  public final int major, minor;
  public final boolean rangeMetadata;

  private ResourcePackFormat(int major, int minor) {
    this.major = major;
    this.minor = minor;
    rangeMetadata = major >= 69;
  }

  public static ResourcePackFormat forVersion(MinecraftVersion v) {
    if (!v.supported())
      throw new IllegalArgumentException("Unknown resource-pack format for Minecraft " + v);
    int format;
    if (v.major == 26)
      return new ResourcePackFormat(
          v.minor == 1 ? 84 : v.minor == 2 ? 88 : 97, v.minor == 3 ? 1 : 0);
    switch (v.minor) {
      case 12:
        format = 3;
        break;
      case 13:
      case 14:
        format = 4;
        break;
      case 15:
        format = 5;
        break;
      case 16:
        format = v.patch < 2 ? 5 : 6;
        break;
      case 17:
        format = 7;
        break;
      case 18:
        format = 8;
        break;
      case 19:
        format = v.patch < 3 ? 9 : v.patch == 3 ? 12 : 13;
        break;
      case 20:
        format = v.patch < 2 ? 15 : v.patch == 2 ? 18 : v.patch < 5 ? 22 : 32;
        break;
      case 21:
        format =
            v.patch < 2
                ? 34
                : v.patch < 4
                    ? 42
                    : v.patch == 4
                        ? 46
                        : v.patch == 5
                            ? 55
                            : v.patch == 6 ? 63 : v.patch < 9 ? 64 : v.patch < 11 ? 69 : 75;
        break;
      default:
        throw new IllegalArgumentException("Unknown resource-pack format: " + v);
    }
    return new ResourcePackFormat(format, 0);
  }

  public JsonObject metadata(String description) {
    JsonObject pack = new JsonObject();
    if (rangeMetadata) {
      pack.add("min_format", tuple());
      pack.add("max_format", tuple());
    } else pack.addProperty("pack_format", major);
    pack.addProperty("description", description);
    return pack;
  }

  private JsonArray tuple() {
    JsonArray tuple = new JsonArray();
    tuple.add(major);
    tuple.add(minor);
    return tuple;
  }

  public boolean matches(JsonObject pack) {
    try {
      if (!rangeMetadata)
        return pack.has("pack_format") && pack.get("pack_format").getAsInt() == major;
      return pack.has("min_format")
          && pack.has("max_format")
          && tuple().equals(pack.get("min_format"))
          && tuple().equals(pack.get("max_format"));
    } catch (IllegalStateException | NumberFormatException e) {
      return false;
    }
  }

  @Override
  public String toString() {
    return major + (rangeMetadata ? "." + minor : "");
  }
}
