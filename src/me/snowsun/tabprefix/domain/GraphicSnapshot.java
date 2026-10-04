package me.snowsun.tabprefix.domain;

import java.util.*;

public final class GraphicSnapshot {
  public final Map<UUID, AssetDescriptor> assets;
  public final Map<String, GraphicPrefix> prefixes;
  public final Map<String, GroupTextPrefix> texts;

  public GraphicSnapshot(
      Map<UUID, AssetDescriptor> assets,
      Map<String, GraphicPrefix> prefixes,
      Map<String, GroupTextPrefix> texts) {
    this.assets = Collections.unmodifiableMap(new HashMap<>(assets));
    this.prefixes = Collections.unmodifiableMap(new HashMap<>(prefixes));
    this.texts = Collections.unmodifiableMap(new HashMap<>(texts));
  }

  public static GraphicSnapshot empty() {
    return new GraphicSnapshot(
        Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
  }

  public AssetDescriptor asset(String group) {
    GraphicPrefix p = prefixes.get(group);
    return p == null ? null : assets.get(p.assetId);
  }

  public int glyphs() {
    int count = 0;
    for (AssetDescriptor a : assets.values()) if (a.assigned()) count += a.frames();
    return count;
  }

  public int animations() {
    int count = 0;
    for (GraphicPrefix p : prefixes.values()) {
      AssetDescriptor a = assets.get(p.assetId);
      if (a != null && a.frames() > 1) count++;
    }
    return count;
  }
}
