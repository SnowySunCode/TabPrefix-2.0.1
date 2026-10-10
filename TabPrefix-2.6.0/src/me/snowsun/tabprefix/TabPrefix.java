package me.snowsun.tabprefix;

import java.util.logging.Level;
import me.snowsun.tabprefix.bootstrap.PluginRuntime;
import me.snowsun.tabprefix.config.ConfigurationManager;
import me.snowsun.tabprefix.config.ConfigurationSnapshot;
import me.snowsun.tabprefix.infrastructure.platform.PlatformInfo;
import me.snowsun.tabprefix.presentation.command.TabPrefixCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class TabPrefix extends JavaPlugin {
  private PluginRuntime runtime;

  @Override
  public void onEnable() {
    try {
      ConfigurationManager manager = new ConfigurationManager(this);
      ConfigurationSnapshot configuration = manager.load();
      PlatformInfo platform = PlatformInfo.detect(getServer());
      configuration.settings.validatePlatform(platform);
      Object permissions = null;
      if (configuration.settings.luckPermsEnabled)
        try {
          Class<?> api =
              Class.forName("net.luckperms.api.LuckPerms", false, getClass().getClassLoader());
          RegisteredServiceProvider<?> provider =
              getServer().getServicesManager().getRegistration(api);
          if (provider != null) permissions = provider.getProvider();
        } catch (ClassNotFoundException | NoClassDefFoundError absent) {
          /* Standalone mode. */
        }
      getLogger()
          .info(
              permissions == null
                  ? "Standalone mode: Bukkit permissions and configured groups."
                  : "LuckPerms integration enabled.");
      PluginCommand command = getCommand("lptab");
      if (command == null)
        throw new IllegalStateException("Command lptab is missing from plugin.yml.");
      runtime = new PluginRuntime(this, manager, configuration, platform, permissions);
      TabPrefixCommand handler =
          new TabPrefixCommand(
              runtime,
              runtime.prefixes(),
              runtime.luckPerms(),
              runtime.messages(),
              runtime.dispatcher(),
              runtime.commands());
      command.setExecutor(handler);
      command.setTabCompleter(handler);
      runtime.start();
    } catch (Exception | LinkageError error) {
      getLogger().log(Level.SEVERE, "Cannot start TabPrefix.", error);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (runtime != null) {
      runtime.close();
      runtime = null;
    }
    getLogger().info("TabPrefix stopped.");
  }
}
