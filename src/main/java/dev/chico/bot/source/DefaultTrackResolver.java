package dev.chico.bot.source;

import java.net.URI;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** YouTube first with SoundCloud fallback, degraded sources skipped. */
public final class DefaultTrackResolver implements TrackResolver {

  private static final String YOUTUBE_SEARCH = "ytsearch:";
  private static final String SOUNDCLOUD_SEARCH = "scsearch:";

  private final Map<Source, TrackLoader> loaders;
  private final Ranker ranker;
  private final CircuitBreaker breaker;
  private final Duration resolveTimeout;

  public DefaultTrackResolver(
      Map<Source, TrackLoader> loaders,
      Ranker ranker,
      CircuitBreaker breaker,
      Duration resolveTimeout) {
    this.loaders = Map.copyOf(loaders);
    this.ranker = ranker;
    this.breaker = breaker;
    this.resolveTimeout = resolveTimeout;
  }

  @Override
  public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
    if (query instanceof UrlQuery url) {
      return resolveUrl(url).thenApply(this::firstOnly);
    }
    String text =
        switch (query) {
          case TextQuery t -> t.text();
          case OcrQuery o -> o.track().title() + " " + o.track().artist();
          case UrlQuery u -> throw new IllegalStateException("unreachable");
        };
    return resolveSearch(text).thenApply(candidates -> ranked(candidates, text));
  }

  private CompletableFuture<List<TrackCandidate>> resolveUrl(UrlQuery query) {
    TrackLoader loader = loaders.get(query.source());
    if (loader == null) {
      return CompletableFuture.completedFuture(List.of());
    }
    return withTimeout(loader.loadUrl(query.uri()))
        .thenApply(
            candidates -> {
              breaker.recordSuccess(query.source());
              return candidates;
            })
        .exceptionally(
            failure -> {
              breaker.recordFailure(query.source());
              return List.of();
            });
  }

  private CompletableFuture<List<TrackCandidate>> resolveSearch(String text) {
    CompletableFuture<List<TrackCandidate>> youtube = searchSource(Source.YOUTUBE, text);
    return youtube.thenCompose(
        candidates -> {
          if (!candidates.isEmpty()) {
            return CompletableFuture.completedFuture(candidates);
          }
          return searchSource(Source.SOUNDCLOUD, text);
        });
  }

  private CompletableFuture<List<TrackCandidate>> searchSource(Source source, String text) {
    TrackLoader loader = loaders.get(source);
    if (loader == null || breaker.isOpen(source)) {
      return CompletableFuture.completedFuture(List.of());
    }
    String prefix = source == Source.YOUTUBE ? YOUTUBE_SEARCH : SOUNDCLOUD_SEARCH;
    return withTimeout(loader.search(prefix + text))
        .thenApply(
            candidates -> {
              breaker.recordSuccess(source);
              return candidates;
            })
        .exceptionally(
            failure -> {
              breaker.recordFailure(source);
              return List.of();
            });
  }

  private <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future) {
    return future.orTimeout(resolveTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  private List<TrackCandidate> ranked(List<TrackCandidate> candidates, String text) {
    return candidates.stream()
        .sorted(Comparator.comparingDouble((TrackCandidate c) -> ranker.score(c, text)).reversed())
        .toList();
  }

  private List<TrackCandidate> firstOnly(List<TrackCandidate> candidates) {
    if (candidates.isEmpty()) {
      return candidates;
    }
    return List.of(candidates.get(0));
  }
}
