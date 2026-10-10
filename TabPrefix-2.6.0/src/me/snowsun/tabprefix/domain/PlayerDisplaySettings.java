package me.snowsun.tabprefix.domain;

import com.google.gson.JsonObject;

/** Player choices affect only this recipient, never server templates or other players. */
public final class PlayerDisplaySettings {
  public final boolean sidebar,
      headerFooter,
      layout,
      nameTags,
      animations,
      bossBars,
      suffixes,
      sorting,
      screen;

  public PlayerDisplaySettings(JsonObject j) {
    screen = DisplayDesign.bool(j, "screen", true);
    sidebar = DisplayDesign.bool(j, "sidebar", true);
    headerFooter = DisplayDesign.bool(j, "headerFooter", true);
    layout = DisplayDesign.bool(j, "layout", true);
    nameTags = DisplayDesign.bool(j, "nameTags", true);
    animations = DisplayDesign.bool(j, "animations", true);
    bossBars = DisplayDesign.bool(j, "bossBars", true);
    suffixes = DisplayDesign.bool(j, "suffixes", true);
    sorting = DisplayDesign.bool(j, "sorting", true);
  }

  public JsonObject json() {
    JsonObject j = new JsonObject();
    j.addProperty("screen", screen);
    j.addProperty("sidebar", sidebar);
    j.addProperty("headerFooter", headerFooter);
    j.addProperty("layout", layout);
    j.addProperty("nameTags", nameTags);
    j.addProperty("animations", animations);
    j.addProperty("bossBars", bossBars);
    j.addProperty("suffixes", suffixes);
    j.addProperty("sorting", sorting);
    return j;
  }
}
