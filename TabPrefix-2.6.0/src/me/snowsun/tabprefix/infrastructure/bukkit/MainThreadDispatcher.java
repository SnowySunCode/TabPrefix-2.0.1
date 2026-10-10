package me.snowsun.tabprefix.infrastructure.bukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

/** Cancels late asynchronous completions after plugin shutdown. */
public final class MainThreadDispatcher
    implements me.snowsun.tabprefix.application.port.MainThreadExecutor, AutoCloseable {
  private final JavaPlugin plugin;
  private final AtomicBoolean open = new AtomicBoolean(true);

  public MainThreadDispatcher(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void execute(Runnable action) {
    if (!open.get()) return;
    Runnable guarded =
        () -> {
          if (open.get() && plugin.isEnabled()) action.run();
        };
    if (Bukkit.isPrimaryThread()) guarded.run();
    else {
      try {
        Bukkit.getScheduler().runTask(plugin, guarded);
      } catch (IllegalPluginAccessException error) {
        if (open.get())
          plugin.getLogger().warning("Cannot dispatch operation: " + error.getMessage());
      }
    }
  }

  @Override
  public void close() {
    open.set(false);
  }
}
