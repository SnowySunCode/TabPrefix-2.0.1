package me.snowsun.tabprefix.domain;

import java.util.UUID;

public final class GraphicPrefix {
  public final String group;
  public final UUID assetId, actor;
  public final long updatedAt;

  public GraphicPrefix(String group, UUID assetId, UUID actor, long time) {
    this.group = GroupTextPrefix.normalizeGroup(group);
    this.assetId = assetId;
    this.actor = actor;
    updatedAt = time;
  }
}
