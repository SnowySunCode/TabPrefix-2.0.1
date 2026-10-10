package com.mojang.authlib;

import com.mojang.authlib.properties.PropertyMap;
import java.util.UUID;

/** Reflection contract fixture, never packaged into the production plugin. */
public final class GameProfile {
  private final UUID id;
  private final String name;
  private final PropertyMap properties = new PropertyMap();

  public GameProfile(UUID id, String name) {
    this.id = id;
    this.name = name;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public PropertyMap getProperties() {
    return properties;
  }
}
