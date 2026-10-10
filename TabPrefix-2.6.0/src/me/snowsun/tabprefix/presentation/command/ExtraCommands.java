package me.snowsun.tabprefix.presentation.command;

import java.util.List;
import org.bukkit.command.CommandSender;

public interface ExtraCommands {
  boolean handle(CommandSender sender, String action, String[] args);

  void help(CommandSender sender);

  void status(CommandSender sender);

  void complete(CommandSender sender, String[] args, List<String> options);

  default void validateText(me.snowsun.tabprefix.domain.GroupTextPrefix prefix) {}

  default void textChanged(CommandSender sender, String group, boolean removed) {}
}
