package me.snowsun.tabprefix.infrastructure.resourcepack;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import me.snowsun.tabprefix.application.GraphicService;
import me.snowsun.tabprefix.application.port.AuditSink;
import me.snowsun.tabprefix.domain.PackRevision;
import me.snowsun.tabprefix.util.*;

/** Serial pack publication; coalesced calls finish after the newest requested snapshot. */
public final class ResourcePackService implements AutoCloseable {
  private final PackBuilder builder;
  private final GraphicService graphics;
  private final AuditSink audit;
  private final AsyncWorker worker = new AsyncWorker("Pack", 1, 8);
  private volatile PackRevision active;
  private volatile String buildError;
  private Consumer<PackRevision> listener = revision -> {};
  private CompletableFuture<PackRevision> running;
  private boolean dirty, rollingBack, closed;

  public ResourcePackService(PackBuilder builder, GraphicService graphics, AuditSink audit) {
    this.builder = builder;
    this.graphics = graphics;
    this.audit = audit;
  }

  public CompletableFuture<Void> initialize() {
    return worker.submit(
        () -> {
          active = builder.load(graphics.snapshot());
          return null;
        });
  }

  public void designs(
      java.util.function.Supplier<me.snowsun.tabprefix.domain.DisplayDesign> designs) {
    builder.designs(designs);
  }

  public synchronized boolean building() {
    return running != null;
  }

  public String legacyError() {
    return buildError == null ? builder.legacyError() : buildError;
  }

  public PackRevision active() {
    return active;
  }

  public void pin(PackRevision revision) {
    builder.pin(revision.hash);
  }

  public synchronized void onPublished(Consumer<PackRevision> listener) {
    this.listener = listener;
  }

  public synchronized CompletableFuture<PackRevision> rebuild() {
    if (closed) return failed();
    if (running != null) {
      if (rollingBack) return running.handle((v, e) -> null).thenCompose(v -> rebuild());
      dirty = true;
      return running;
    }
    CompletableFuture<PackRevision> result = new CompletableFuture<>();
    running = result;
    dirty = false;
    rollingBack = false;
    worker
        .submit(
            () -> {
              PackRevision latest;
              try {
                while (true) {
                  synchronized (this) {
                    dirty = false;
                  }
                  audit.record("pack-build-started", java.util.Collections.emptyMap());
                  latest = builder.build(graphics.snapshot());
                  active = latest;
                  buildError = null;
                  audit.record(
                      "pack-build-completed",
                      Values.of(
                          "glyphs", latest.glyphs, "atlases", latest.atlases, "hash", latest.hash));
                  listener.accept(latest);
                  synchronized (this) {
                    if (dirty && !closed) continue;
                    running = null;
                    break;
                  }
                }
                result.complete(latest);
              } catch (Exception | LinkageError e) {
                buildError = Failures.message(e);
                audit.record("pack-build-failed", Values.of("reason", Failures.message(e)));
                synchronized (this) {
                  running = null;
                }
                result.completeExceptionally(e);
              }
              return null;
            })
        .whenComplete(
            (v, e) -> {
              if (e != null) {
                synchronized (this) {
                  if (running == result) running = null;
                }
                result.completeExceptionally(e);
              }
            });
    return result;
  }

  public synchronized CompletableFuture<PackRevision> rollback(String hash) {
    if (closed) return failed();
    if (running != null) return running.handle((v, e) -> null).thenCompose(v -> rollback(hash));
    CompletableFuture<PackRevision> result = new CompletableFuture<>();
    running = result;
    rollingBack = true;
    worker
        .submit(
            () -> {
              try {
                PackRevision revision = builder.rollback(hash, graphics.snapshot());
                active = revision;
                listener.accept(revision);
                audit.record("pack-rollback", Values.of("hash", hash));
                synchronized (this) {
                  running = null;
                }
                result.complete(revision);
              } catch (Exception | LinkageError e) {
                synchronized (this) {
                  running = null;
                }
                result.completeExceptionally(e);
              }
              return null;
            })
        .whenComplete(
            (v, e) -> {
              if (e != null) {
                synchronized (this) {
                  if (running == result) running = null;
                }
                result.completeExceptionally(e);
              }
            });
    return result;
  }

  private static CompletableFuture<PackRevision> failed() {
    CompletableFuture<PackRevision> f = new CompletableFuture<>();
    f.completeExceptionally(new IllegalStateException("Pack service closed."));
    return f;
  }

  @Override
  public void close() {
    synchronized (this) {
      closed = true;
    }
    worker.close();
  }
}
