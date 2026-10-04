package me.snowsun.tabprefix.application.port;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface GroupDirectory {
  CompletableFuture<Boolean> groupExistsAsync(String group);

  List<String> groups();
}
