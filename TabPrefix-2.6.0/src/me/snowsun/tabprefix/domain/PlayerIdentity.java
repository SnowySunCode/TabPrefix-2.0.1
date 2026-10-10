package me.snowsun.tabprefix.domain;

import java.util.UUID;

/** A detached LuckPerms snapshot, safe to pass across module boundaries. */
public final class PlayerIdentity {
  public final UUID playerId;
  public final String group;
  public final String luckPermsPrefix;
  public final String luckPermsSuffix;
  public final int groupWeight;

  public PlayerIdentity(UUID playerId, String group, String luckPermsPrefix) {
    this(playerId, group, luckPermsPrefix, "", 0);
  }

  public PlayerIdentity(
      UUID playerId,
      String group,
      String luckPermsPrefix,
      String luckPermsSuffix,
      int groupWeight) {
    this.playerId = playerId;
    this.group = group;
    this.luckPermsPrefix = luckPermsPrefix == null ? "" : luckPermsPrefix;
    this.luckPermsSuffix = luckPermsSuffix == null ? "" : luckPermsSuffix;
    this.groupWeight = groupWeight;
  }
}
