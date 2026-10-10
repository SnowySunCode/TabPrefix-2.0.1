package com.mojang.authlib.properties;

import com.google.common.collect.*;

public final class PropertyMap extends ForwardingMultimap<String, String> {
  private final Multimap<String, String> values = HashMultimap.create();

  protected Multimap<String, String> delegate() {
    return values;
  }
}
