package me.snowsun.tabprefix.application.port;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import me.snowsun.tabprefix.domain.*;

public interface GraphicRepository {
  CompletableFuture<GraphicSnapshot> load();

  CompletableFuture<Void> register(AssetDescriptor asset);

  CompletableFuture<Void> saveDraft(
      PrefixDraft draft, String hash, boolean singleUse, boolean bound);

  CompletableFuture<String> codeGroup(String hash);

  CompletableFuture<GraphicSnapshot> apply(
      String hash, UUID actor, String currentGroup, boolean manage, int start, int end, long now);

  CompletableFuture<GraphicSnapshot> remove(String group);

  CompletableFuture<List<UUID>> cleanup(long now);
}
