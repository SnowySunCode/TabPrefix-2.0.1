package me.snowsun.tabprefix.application;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import me.snowsun.tabprefix.application.port.GroupPrefixRepository;
import me.snowsun.tabprefix.domain.GroupTextPrefix;

/** Orders mutations and publishes immutable snapshots only after successful storage. */
public final class PrefixService implements AutoCloseable {
  private final GroupPrefixRepository repository;
  private final AtomicReference<Map<String, GroupTextPrefix>> cache =
      new AtomicReference<>(Collections.emptyMap());
  private final AtomicInteger pending = new AtomicInteger();
  private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
  private volatile boolean ready;
  private volatile boolean closed;

  public PrefixService(GroupPrefixRepository repository) {
    this.repository = repository;
  }

  public synchronized CompletableFuture<Void> initialize() {
    if (closed || ready)
      return failed("Prefix service cannot be initialized in its current state.");
    CompletableFuture<Void> loading =
        repository
            .findAll()
            .thenAccept(
                rows -> {
                  Map<String, GroupTextPrefix> loaded = new HashMap<>();
                  for (GroupTextPrefix row : rows) loaded.put(row.group(), row);
                  cache.set(Collections.unmodifiableMap(loaded));
                  if (!closed) ready = true;
                });
    tail = loading.handle((value, error) -> null);
    return loading;
  }

  public boolean ready() {
    return ready && !closed;
  }

  public int count() {
    return cache.get().size();
  }

  public GroupTextPrefix find(String group) {
    return group == null ? null : cache.get().get(GroupTextPrefix.normalizeGroup(group));
  }

  public CompletableFuture<Void> save(GroupTextPrefix prefix) {
    return enqueue(
        () ->
            repository
                .save(prefix)
                .thenRun(
                    () -> {
                      Map<String, GroupTextPrefix> changed = new HashMap<>(cache.get());
                      changed.put(prefix.group(), prefix);
                      cache.set(Collections.unmodifiableMap(changed));
                    }));
  }

  public CompletableFuture<Boolean> remove(String group) {
    String key = GroupTextPrefix.normalizeGroup(group);
    return enqueue(
        () ->
            repository
                .delete(key)
                .thenApply(
                    removed -> {
                      Map<String, GroupTextPrefix> changed = new HashMap<>(cache.get());
                      changed.remove(key);
                      cache.set(Collections.unmodifiableMap(changed));
                      return removed;
                    }));
  }

  /** Publish both caches in the mutation chain after the same committed transaction. */
  public <T> CompletableFuture<T> commitExternal(
      Supplier<CompletableFuture<T>> action,
      java.util.function.Function<T, java.util.Collection<GroupTextPrefix>> rows,
      java.util.function.Consumer<T> publish) {
    return enqueue(
        () ->
            action
                .get()
                .thenApply(
                    result -> {
                      Map<String, GroupTextPrefix> next = new HashMap<>();
                      for (GroupTextPrefix row : rows.apply(result)) next.put(row.group(), row);
                      cache.set(Collections.unmodifiableMap(next));
                      publish.accept(result);
                      return result;
                    }));
  }

  public <T> CompletableFuture<T> synchronize(Supplier<CompletableFuture<T>> action) {
    return enqueue(action);
  }

  private synchronized <T> CompletableFuture<T> enqueue(Supplier<CompletableFuture<T>> action) {
    if (!ready()) return failed("Prefix service is not ready.");
    if (pending.get() >= 128) return failed("Too many pending prefix operations.");
    pending.incrementAndGet();
    CompletableFuture<T> result =
        tail.handle((ignored, error) -> null).thenCompose(ignored -> action.get());
    tail = result.handle((ignored, error) -> null);
    result.whenComplete((ignored, error) -> pending.decrementAndGet());
    return result;
  }

  private static <T> CompletableFuture<T> failed(String message) {
    CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(new IllegalStateException(message));
    return future;
  }

  @Override
  public void close() {
    closed = true;
    ready = false;
  }
}
