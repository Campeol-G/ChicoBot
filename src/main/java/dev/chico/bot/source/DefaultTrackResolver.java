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

  /**
   * URL path segments that carry no searchable text. Query strings never reach
   * here ({@link URI#getPath} strips them), so link-only parameters need no
   * special handling.
   */
  private static final java.util.Set<String> SLUG_STOP_SEGMENTS =
      java.util.Set.of(
          "sets",
          "likes",
          "tracks",
          "albums",
          "reposts",
          "popular-tracks",
          "followers",
          "following",
          "comments",
          "recommended",
          "stations",
          "groups",
          "people",
          "watch",
          "playlist");

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
      return resolveUrl(url);
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
        .thenCompose(
            candidates -> {
              breaker.recordSuccess(query.source());
              if (candidates.isEmpty()) {
                return fallbackSearch(query);
              }
              return CompletableFuture.completedFuture(List.of(candidates.get(0)));
            })
        .exceptionallyCompose(
            failure -> {
              breaker.recordFailure(query.source());
              return fallbackSearch(query);
            });
  }

  /**
   * Direct SoundCloud loads fail when Lavaplayer cannot play the track's
   * formats. Searching the slug text (usually artist + title) still finds an
   * equivalent, most often on YouTube. Only SoundCloud links fall back: direct
   * YouTube loads either work or have no searchable slug.
   */
  private CompletableFuture<List<TrackCandidate>> fallbackSearch(UrlQuery query) {
    if (query.source() != Source.SOUNDCLOUD) {
      return CompletableFuture.completedFuture(List.of());
    }
    java.util.Optional<String> full = slugText(query.uri());
    java.util.Optional<String> titleOnly = titleSlug(query.uri());
    CompletableFuture<List<TrackCandidate>> first =
        full.map(this::searchRanked).orElseGet(() -> CompletableFuture.completedFuture(List.of()));
    // The uploader segment often names an account, not the artist, and an
    // over-specific query matches nothing. Retry with the title slug alone.
    if (titleOnly.isPresent() && !titleOnly.equals(full)) {
      String title = titleOnly.orElseThrow();
      first = first.thenCompose(
          candidates ->
              candidates.isEmpty() ? searchRanked(title) : CompletableFuture.completedFuture(candidates));
    }
    return first;
  }

  private CompletableFuture<List<TrackCandidate>> searchRanked(String text) {
    return resolveSearch(text).thenApply(candidates -> ranked(candidates, text));
  }

  static java.util.Optional<String> slugText(URI uri) {
    List<String> words = slugWords(uri, false);
    if (words.isEmpty()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(String.join(" ", words));
  }

  static java.util.Optional<String> titleSlug(URI uri) {
    List<String> words = slugWords(uri, true);
    if (words.isEmpty()) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(String.join(" ", words));
  }

  private static List<String> slugWords(URI uri, boolean lastSegmentOnly) {
    String path = uri.getPath();
    if (path == null || path.isBlank()) {
      return List.of();
    }
    List<String> segments = new java.util.ArrayList<>();
    for (String segment : path.split("/")) {
      String lowered = segment.toLowerCase(java.util.Locale.ROOT);
      if (segment.isEmpty()
          || SLUG_STOP_SEGMENTS.contains(lowered)
          || lowered.startsWith("s-")) {
        continue;
      }
      segments.add(segment);
    }
    if (lastSegmentOnly && !segments.isEmpty()) {
      segments = segments.subList(segments.size() - 1, segments.size());
    }
    List<String> words = new java.util.ArrayList<>();
    for (String segment : segments) {
      for (String word : segment.split("[^A-Za-z0-9]+")) {
        if (word.length() >= 2) {
          words.add(word);
        }
      }
    }
    return words;
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
}
