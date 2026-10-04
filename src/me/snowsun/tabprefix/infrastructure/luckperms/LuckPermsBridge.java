package me.snowsun.tabprefix.infrastructure.luckperms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import me.snowsun.tabprefix.domain.PlayerIdentity;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.group.GroupDataRecalculateEvent;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.event.user.UserLoadEvent;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Reads cached LuckPerms state; performs no permission-database I/O. */
public final class LuckPermsBridge
    implements me.snowsun.tabprefix.application.port.GroupDirectory, AutoCloseable {
  private final JavaPlugin plugin;
  private final LuckPerms api;
  private final List<EventSubscription<?>> subscriptions = new ArrayList<>();

  public LuckPermsBridge(JavaPlugin plugin, LuckPerms api) {
    this.plugin = plugin;
    this.api = api;
  }

  public PlayerIdentity identity(Player player, boolean primaryGroup) {
    User user = api.getUserManager().getUser(player.getUniqueId());
    if (user == null) return null;
    QueryOptions options = api.getContextManager().getQueryOptions(player);
    String group = user.getPrimaryGroup();
    if (!primaryGroup) {
      group =
          user.getInheritedGroups(options).stream()
              .sorted(
                  Comparator.comparingInt((Group item) -> item.getWeight().orElse(0))
                      .reversed()
                      .thenComparing(Group::getName))
              .map(Group::getName)
              .findFirst()
              .orElse(group);
    }
    return new PlayerIdentity(
        player.getUniqueId(), group, user.getCachedData().getMetaData(options).getPrefix());
  }

  public boolean groupExists(String group) {
    return api.getGroupManager().getGroup(group) != null;
  }

  public java.util.concurrent.CompletableFuture<Boolean> groupExistsAsync(String group) {
    if (groupExists(group)) return java.util.concurrent.CompletableFuture.completedFuture(true);
    return api.getGroupManager().loadGroup(group).thenApply(java.util.Optional::isPresent);
  }

  public List<String> groups() {
    List<String> result = new ArrayList<>();
    for (Group group : api.getGroupManager().getLoadedGroups()) result.add(group.getName());
    java.util.Collections.sort(result);
    return result;
  }

  public void listen(boolean updates, Consumer<UUID> refresh, Runnable refreshAll) {
    close();
    subscriptions.add(
        api.getEventBus()
            .subscribe(
                plugin,
                UserLoadEvent.class,
                event -> refresh.accept(event.getUser().getUniqueId())));
    if (updates) {
      subscriptions.add(
          api.getEventBus()
              .subscribe(
                  plugin,
                  UserDataRecalculateEvent.class,
                  event -> refresh.accept(event.getUser().getUniqueId())));
      subscriptions.add(
          api.getEventBus()
              .subscribe(plugin, GroupDataRecalculateEvent.class, event -> refreshAll.run()));
    }
  }

  @Override
  public void close() {
    for (EventSubscription<?> subscription : subscriptions) subscription.close();
    subscriptions.clear();
  }
}
