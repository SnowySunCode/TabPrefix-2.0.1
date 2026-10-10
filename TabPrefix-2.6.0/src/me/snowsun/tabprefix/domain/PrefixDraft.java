package me.snowsun.tabprefix.domain;

import java.util.UUID;

public final class PrefixDraft {
  public final UUID id, owner, assetId;
  public final String group, text;
  public final TextFormat format;
  public final long createdAt, expiresAt;

  public PrefixDraft(
      UUID owner, String group, UUID asset, String text, TextFormat format, long now, long expiry) {
    id = UUID.randomUUID();
    this.owner = java.util.Objects.requireNonNull(owner);
    this.group = GroupTextPrefix.normalizeGroup(group);
    assetId = asset;
    this.text = text;
    this.format = java.util.Objects.requireNonNull(format);
    createdAt = now;
    expiresAt = expiry;
    if (expiry <= now) throw new IllegalArgumentException("Invalid expiry.");
    if (text != null) new GroupTextPrefix(group, text, format, owner, now);
    if (asset == null && text == null)
      throw new IllegalArgumentException("Choose an image or a text prefix.");
  }
}
