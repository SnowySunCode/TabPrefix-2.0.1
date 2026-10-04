package me.snowsun.tabprefix.application;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import me.snowsun.tabprefix.application.port.GraphicRepository;
import me.snowsun.tabprefix.config.FeatureSettings;
import me.snowsun.tabprefix.domain.*;
import me.snowsun.tabprefix.util.Digests;

public final class GraphicService {
  public static final class SaveResult {
    public final String code;
    public final long expiresAt;

    SaveResult(String code, long expiry) {
      this.code = code;
      expiresAt = expiry;
    }
  }

  private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  private final GraphicRepository repository;
  private final PrefixService texts;
  private final SecureRandom random = new SecureRandom();
  private final AtomicReference<GraphicSnapshot> cache =
      new AtomicReference<>(GraphicSnapshot.empty());
  private volatile FeatureSettings settings;

  public GraphicService(
      GraphicRepository repository, PrefixService texts, FeatureSettings settings) {
    this.repository = repository;
    this.texts = texts;
    this.settings = settings;
  }

  public CompletableFuture<Void> initialize() {
    return repository
        .load()
        .thenAccept(
            snapshot -> {
              for (AssetDescriptor asset : snapshot.assets.values())
                if (asset.assigned()) {
                  if (asset.width != settings.cellWidth || asset.height != settings.cellHeight)
                    throw new IllegalArgumentException(
                        "Stored glyph cell size differs from configuration. Restore the original"
                            + " glyph geometry.");
                  for (int code : asset.glyphs())
                    if (code < settings.start || code > settings.end)
                      throw new IllegalArgumentException(
                          "Stored glyph lies outside the configured range. Restore the original"
                              + " Unicode range.");
                }
              cache.set(snapshot);
            });
  }

  public GraphicSnapshot snapshot() {
    return cache.get();
  }

  public void reload(FeatureSettings settings) {
    this.settings = settings;
  }

  public CompletableFuture<Void> register(AssetDescriptor asset) {
    return texts.synchronize(
        () -> {
          if (cache.get().assets.containsKey(asset.id))
            return CompletableFuture.completedFuture(null);
          return repository
              .register(asset)
              .thenCompose(v -> repository.load())
              .thenAccept(cache::set);
        });
  }

  public CompletableFuture<SaveResult> save(
      UUID owner, String group, UUID asset, String text, TextFormat format) {
    FeatureSettings f = settings;
    if (!f.codes) return failed(new IllegalStateException("Save codes are disabled."));
    long now = System.currentTimeMillis();
    PrefixDraft draft =
        new PrefixDraft(owner, group, asset, text, format, now, now + f.codeMinutes * 60000L);
    StringBuilder raw = new StringBuilder();
    for (int i = 0; i < f.codeLength; i++) raw.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    String value = raw.toString();
    String displayed =
        value.substring(0, f.separatorPosition)
            + f.codeSeparator
            + value.substring(f.separatorPosition);
    return repository
        .saveDraft(draft, Digests.sha256(value), f.singleUse, f.bound)
        .thenApply(v -> new SaveResult(displayed, draft.expiresAt));
  }

  public CompletableFuture<String> codeGroup(String code) {
    try {
      return repository.codeGroup(hash(code));
    } catch (RuntimeException e) {
      return failed(e);
    }
  }

  public CompletableFuture<Void> apply(String code, UUID actor, String group, boolean manage) {
    try {
      FeatureSettings f = settings;
      if (!f.codes) return failed(new IllegalStateException("Save codes are disabled."));
      String hash = hash(code);
      return texts
          .commitExternal(
              () ->
                  repository.apply(
                      hash, actor, group, manage, f.start, f.end, System.currentTimeMillis()),
              s -> s.texts.values(),
              cache::set)
          .thenApply(v -> null);
    } catch (RuntimeException e) {
      return failed(e);
    }
  }

  public CompletableFuture<Void> remove(String group) {
    return texts
        .commitExternal(() -> repository.remove(group), s -> s.texts.values(), cache::set)
        .thenApply(v -> null);
  }

  public CompletableFuture<List<UUID>> cleanup(long now) {
    return texts.synchronize(
        () ->
            repository
                .cleanup(now)
                .thenCompose(
                    removed ->
                        repository
                            .load()
                            .thenApply(
                                s -> {
                                  cache.set(s);
                                  return removed;
                                })));
  }

  private String hash(String code) {
    FeatureSettings f = settings;
    String raw =
        code == null
            ? ""
            : code.trim()
                .toUpperCase(Locale.ROOT)
                .replace(f.codeSeparator.isEmpty() ? "\u0000" : f.codeSeparator, "");
    if (raw.length() != f.codeLength) throw new SaveCodeException(SaveCodeException.Reason.INVALID);
    for (char c : raw.toCharArray())
      if (new String(ALPHABET).indexOf(c) < 0)
        throw new SaveCodeException(SaveCodeException.Reason.INVALID);
    return Digests.sha256(raw);
  }

  private static <T> CompletableFuture<T> failed(Throwable e) {
    CompletableFuture<T> r = new CompletableFuture<>();
    r.completeExceptionally(e);
    return r;
  }
}
