package me.snowsun.tabprefix.presentation.message;

import java.util.Map;
import org.bukkit.command.CommandSender;

public interface MessageSink {
  void send(CommandSender sender, String key);

  void send(CommandSender sender, String key, Map<String, String> variables);
}
