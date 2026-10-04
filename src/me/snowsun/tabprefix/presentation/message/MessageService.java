package me.snowsun.tabprefix.presentation.message;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.snowsun.tabprefix.config.ConfigurationSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Sends directly to Bukkit senders; no audience registry can discard command replies. */
public final class MessageService implements MessageSink, AutoCloseable {
  private final Logger logger;
  private final AtomicBoolean closed = new AtomicBoolean(), richWarning = new AtomicBoolean();
  private final java.util.Set<String> missing = ConcurrentHashMap.newKeySet();
  private final java.util.Set<String> invalid = ConcurrentHashMap.newKeySet();
  private volatile State state;

  private static final class State {
    final Map<String, String> catalog;
    final TextRenderer renderer;

    State(ConfigurationSnapshot config) {
      catalog = config.messages;
      renderer = new TextRenderer(config.settings);
    }
  }

  public MessageService(JavaPlugin plugin, ConfigurationSnapshot config) {
    this(plugin.getLogger(), config);
  }

  public MessageService(Logger logger, ConfigurationSnapshot config) {
    this.logger = java.util.Objects.requireNonNull(logger);
    state = new State(config);
  }

  public void reload(ConfigurationSnapshot config) {
    state = new State(config);
    missing.clear();
    invalid.clear();
  }

  public void send(CommandSender sender, String key) {
    send(sender, key, Collections.emptyMap());
  }

  public void send(CommandSender sender, String key, Map<String, String> variables) {
    if (!available(sender)) return;
    State current = state;
    Component component;
    try {
      component = render(current, key, variables);
    } catch (RuntimeException | LinkageError error) {
      invalid(key, error);
      component = Component.text("[TabPrefix] Cannot render message: " + key);
    }
    deliver(sender, current, component);
  }

  public void link(CommandSender sender, String key, Map<String, String> variables, String url) {
    if (!available(sender)) return;
    State current = state;
    Component component;
    try {
      component =
          current.renderer.templateLink(current.catalog.getOrDefault(key, "<url>"), variables, url);
    } catch (RuntimeException | LinkageError error) {
      invalid(key, error);
      component = Component.text("[TabPrefix] Editor: " + url);
    }
    deliver(sender, current, component);
  }

  public String plain(String key, Map<String, String> variables) {
    State current = state;
    return current.renderer.plain(render(current, key, variables));
  }

  private boolean available(CommandSender sender) {
    return !closed.get() && (!(sender instanceof Player) || ((Player) sender).isOnline());
  }

  private void deliver(CommandSender sender, State current, Component component) {
    if (sender instanceof Player && interactive(component)) {
      try {
        // The server owns the Bungee API; bridge by JSON, without touching its Gson instance.
        ((Player) sender)
            .spigot()
            .sendMessage(
                ChatMessageType.SYSTEM,
                ComponentSerializer.parse(GsonComponentSerializer.gson().serialize(component)));
        return;
      } catch (RuntimeException | LinkageError error) {
        if (richWarning.compareAndSet(false, true))
          logger.warning(
              "Rich message delivery unavailable ("
                  + error.getClass().getSimpleName()
                  + "); using Bukkit text messages. Editor URLs remain readable.");
      }
    }
    sender.sendMessage(current.renderer.legacy(component));
  }

  private static boolean interactive(Component component) {
    if (component.clickEvent() != null
        || component.hoverEvent() != null
        || component.insertion() != null) return true;
    for (Component child : component.children()) if (interactive(child)) return true;
    return false;
  }

  private void invalid(String key, Throwable error) {
    if (invalid.add(key))
      logger.warning(
          "Cannot render message " + key + " (" + error.getClass().getSimpleName() + ").");
  }

  private Component render(State current, String key, Map<String, String> variables) {
    String template = current.catalog.get(key);
    if (template == null) {
      if (missing.add(key)) logger.warning("Missing message: " + key);
      return Component.text("[TabPrefix] Missing message: " + key);
    }
    return current.renderer.template(template, variables);
  }

  @Override
  public void close() {
    closed.set(true);
  }
}
