package me.snowsun.tabprefix.presentation.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.snowsun.tabprefix.config.PluginSettings;
import me.snowsun.tabprefix.domain.TextFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Dynamic values are unparsed text, never executable MiniMessage fragments. */
public final class TextRenderer {
  private final PluginSettings settings;
  private final MiniMessage mini = MiniMessage.miniMessage();
  private final LegacyComponentSerializer inputLegacy =
      LegacyComponentSerializer.builder().character('&').hexColors().build();
  private final LegacyComponentSerializer outputLegacy =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();

  public TextRenderer(PluginSettings settings) {
    this.settings = settings;
  }

  public Component template(String source, Map<String, String> variables) {
    if (!settings.miniMessageEnabled) {
      String plain = mini.stripTags(source);
      for (Map.Entry<String, String> value : variables.entrySet()) {
        plain = plain.replace("<" + value.getKey() + ">", value.getValue());
      }
      return Component.text(plain);
    }
    TagResolver.Builder resolvers = TagResolver.builder();
    variables.forEach((name, value) -> resolvers.resolver(Placeholder.unparsed(name, value)));
    return policy(mini.deserialize(source, resolvers.build()));
  }

  public Component prefixComponent(String text, TextFormat format) {
    Component component =
        policy(
            format == TextFormat.LEGACY
                ? inputLegacy.deserialize(text.replace('§', '&'))
                : template(text, Collections.emptyMap()));
    String plain = plain(component);
    for (int i = 0; i < plain.length(); i++)
      if (Character.isISOControl(plain.charAt(i)))
        throw new IllegalArgumentException(
            "Rendered prefix must be a single line without control characters.");
    return component;
  }

  public Component templateLink(String source, Map<String, String> variables, String url) {
    String cleaned = source.replace("<click:open_url:'<url>'>", "").replace("</click>", "");
    TagResolver.Builder tags = TagResolver.builder();
    variables.forEach((name, value) -> tags.resolver(Placeholder.unparsed(name, value)));
    tags.resolver(
        Placeholder.component("url", Component.text(url).clickEvent(ClickEvent.openUrl(url))));
    return settings.miniMessageEnabled
        ? policy(mini.deserialize(cleaned, tags.build()))
        : Component.text(url);
  }

  public Component templateAction(String source, Map<String, String> variables, String command) {
    // The action is typed data. No command or interface label is parsed as MiniMessage.
    Component line = template(source, variables);
    return settings.clicks ? line.clickEvent(ClickEvent.runCommand(command)) : line;
  }

  public String prefix(String text, TextFormat format) {
    return legacy(prefixComponent(text, format));
  }

  /** Display placeholders are typed components; player names/world names never become markup. */
  public Component display(String source, Map<String, String> variables) {
    Map<String, Component> values = new java.util.LinkedHashMap<>();
    variables.forEach(
        (name, value) ->
            values.put(
                name,
                name.equals("prefix")
                        || name.equals("suffix")
                        || name.equals("display_name")
                        || name.equals("tab_name")
                    ? outputLegacy.deserialize(value)
                    : Component.text(value)));
    if (source.indexOf('<') >= 0 && settings.miniMessageEnabled) {
      TagResolver.Builder tags = TagResolver.builder();
      for (Map.Entry<String, Component> e : values.entrySet()) {
        source = source.replace("{" + e.getKey() + "}", "<tp_" + e.getKey() + ">");
        tags.resolver(Placeholder.component("tp_" + e.getKey(), e.getValue()));
      }
      return policy(mini.deserialize(source, tags.build()));
    }
    Component result =
        inputLegacy.deserialize(
            (settings.miniMessageEnabled ? source : mini.stripTags(source)).replace('§', '&'));
    result =
        result.replaceText(
            net.kyori.adventure.text.TextReplacementConfig.builder()
                .match(java.util.regex.Pattern.compile("\\{([a-z_]+)\\}"))
                .replacement(
                    (match, builder) -> values.getOrDefault(match.group(1), builder.build()))
                .build());
    return policy(result);
  }

  public String legacy(Component component) {
    return outputLegacy.serialize(policy(component));
  }

  public String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private Component policy(Component component) {
    Style.Builder style = component.style().toBuilder();
    if (!settings.colors) style.color(null);
    else if (!settings.minecraft.rgb() && component.color() != null)
      style.color(NamedTextColor.nearestTo(component.color()));
    if (!settings.decorations) {
      for (TextDecoration decoration : TextDecoration.values())
        style.decoration(decoration, TextDecoration.State.NOT_SET);
    }
    if (!settings.clicks) {
      style.clickEvent(null);
      style.insertion(null);
    }
    if (!settings.hovers) style.hoverEvent(null);
    else if (component.hoverEvent() != null
        && component.hoverEvent().action() == HoverEvent.Action.SHOW_TEXT) {
      style.hoverEvent(HoverEvent.showText(policy((Component) component.hoverEvent().value())));
    }
    List<Component> children = new ArrayList<>();
    for (Component child : component.children()) children.add(policy(child));
    return component.style(style.build()).children(children);
  }
}
