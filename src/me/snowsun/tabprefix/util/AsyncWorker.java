package me.snowsun.tabprefix.util;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded daemon executor; every accepted future is completed even on shutdown. */
public final class AsyncWorker implements AutoCloseable {
  @FunctionalInterface
  public interface Work<T> {
    T run() throws Exception;
  }

  private final ThreadPoolExecutor pool;
  private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();

  public AsyncWorker(String name, int threads, int capacity) {
    pool =
        new ThreadPoolExecutor(
            threads,
            threads,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(capacity),
            r -> {
              Thread t = new Thread(r, "TabPrefix-" + name);
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  public <T> CompletableFuture<T> submit(Work<T> work) {
    CompletableFuture<T> result = new CompletableFuture<>();
    if (closed.get()) {
      result.completeExceptionally(new IllegalStateException("Worker is closed."));
      return result;
    }
    pending.add(result);
    try {
      pool.execute(
          () -> {
            try {
              result.complete(work.run());
            } catch (Exception | LinkageError e) {
              result.completeExceptionally(e);
            } finally {
              pending.remove(result);
            }
          });
    } catch (RejectedExecutionException e) {
      pending.remove(result);
      result.completeExceptionally(e);
    }
    return result;
  }

  public void closeGracefully(long milliseconds) {
    if (!closed.compareAndSet(false, true)) return;
    pool.shutdown();
    try {
      if (!pool.awaitTermination(milliseconds, TimeUnit.MILLISECONDS)) pool.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
    for (CompletableFuture<?> future : pending)
      future.completeExceptionally(new IllegalStateException("Worker stopped."));
    pending.clear();
  }

  @Override
  public void close() {
    closeGracefully(1000);
  }
}
