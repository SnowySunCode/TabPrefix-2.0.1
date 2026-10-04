package me.snowsun.tabprefix.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable, persistent group override. Does not modify LuckPerms nodes. */
public final class GroupTextPrefix {
  private final String group;
  private final String text;
  private final TextFormat format;
  private final UUID updatedBy;
  private final long updatedAt;

  public GroupTextPrefix(
      String group, String text, TextFormat format, UUID updatedBy, long updatedAt) {
    this.group = normalizeGroup(group);
    this.text = Objects.requireNonNull(text, "text");
    if (text.trim().isEmpty() || text.length() > 512) {
      throw new IllegalArgumentException("Prefix must contain 1-512 characters.");
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isISOControl(text.charAt(i))) {
        throw new IllegalArgumentException(
            "Prefix must be a single line without control characters.");
      }
    }
    this.format = Objects.requireNonNull(format, "format");
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public static String normalizeGroup(String group) {
    String value = Objects.requireNonNull(group, "group").trim().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.length() > 128) {
      throw new IllegalArgumentException("Group name must contain 1-128 characters.");
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        throw new IllegalArgumentException("Group name contains control characters.");
      }
    }
    return value;
  }

  public String group() {
    return group;
  }

  public String text() {
    return text;
  }

  public TextFormat format() {
    return format;
  }

  public UUID updatedBy() {
    return updatedBy;
  }

  public long updatedAt() {
    return updatedAt;
  }
}
