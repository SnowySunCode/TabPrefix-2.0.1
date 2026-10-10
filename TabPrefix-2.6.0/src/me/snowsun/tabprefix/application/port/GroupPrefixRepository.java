package me.snowsun.tabprefix.application.port;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.snowsun.tabprefix.domain.GroupTextPrefix;

/** All futures complete after the corresponding database operation has committed. */
public interface GroupPrefixRepository {
  CompletableFuture<List<GroupTextPrefix>> findAll();

  CompletableFuture<Void> save(GroupTextPrefix prefix);

  CompletableFuture<Boolean> delete(String group);
}
