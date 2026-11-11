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

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(DefaultTrackResolver.class);

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
  private final boolean parallelSearch;
  private final Duration scRetryDelay;

  static final String SC_FORMAT_ERROR = "Did not detect any supported formats";

  // Calibration: 0.3 lets a near-tie go to the provider's pick, while a clear
  // text winner still wins (the "undressed" case).
  private static final double TEXT_WEIGHT = 0.7;
  private static final double RANK_WEIGHT = 0.3;

  public DefaultTrackResolver(
      Map<Source, TrackLoader> loaders,
      Ranker ranker,
      CircuitBreaker breaker,
      Duration resolveTimeout) {
    this(loaders, ranker, breaker, resolveTimeout, true);
  }

  public DefaultTrackResolver(
      Map<Source, TrackLoader> loaders,
      Ranker ranker,
      CircuitBreaker breaker,
      Duration resolveTimeout,
      boolean parallelSearch) {
    this(loaders, ranker, breaker, resolveTimeout, parallelSearch, Duration.ofSeconds(2));
  }

  DefaultTrackResolver(
      Map<Source, TrackLoader> loaders,
      Ranker ranker,
      CircuitBreaker breaker,
      Duration resolveTimeout,
      boolean parallelSearch,
      Duration scRetryDelay) {
    this.loaders = Map.copyOf(loaders);
    this.ranker = ranker;
    this.breaker = breaker;
    this.resolveTimeout = resolveTimeout;
    this.parallelSearch = parallelSearch;
    this.scRetryDelay = scRetryDelay;
  }

  @Override
  public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
    if (query instanceof UrlQuery url) {
      return resolveUrl(url);
    }
    if (query instanceof OcrQuery ocr) {
      String text = ocr.track().title() + " " + ocr.track().artist();
      if (parallelSearch) {
        // Both sources rank together; the threshold still decides.
        return resolveSearch(text).thenApply(candidates -> rankedAboveThreshold(candidates, text));
      }
      // YouTube results below the threshold do not count: SoundCloud still
      // gets its chance before the track becomes "not found".
      return searchSource(Source.YOUTUBE, text)
          .thenCompose(
              youtube ->
                  rankedAboveThreshold(youtube, text).isEmpty()
                      ? searchSource(Source.SOUNDCLOUD, text)
                          .thenApply(
                              soundcloud -> rankedAboveThreshold(soundcloud, text))
                      : CompletableFuture.completedFuture(rankedAboveThreshold(youtube, text)));
    }
    if (query instanceof TextQuery text) {
      return resolveText(text.text());
    }
    throw new IllegalArgumentException("unsupported query: " + query.getClass());
  }

  /**
   * Batch imports have no per-track user review, so an OCR query whose best
   * match is poor becomes "not found" instead of queueing an unrelated video.
   * Single `/play` queries keep the old order-only behavior: the user sees
   * now-playing and skips when it is wrong.
   */
  private List<TrackCandidate> rankedAboveThreshold(
      List<TrackCandidate> candidates, String text) {
    double threshold = ranker.threshold();
    return candidates.stream()
        .sorted(Comparator.comparingDouble((TrackCandidate c) -> ranker.score(c, text)).reversed()
            .thenComparing(
                c -> c.duration() == null ? Long.MAX_VALUE : c.duration().getSeconds()))
        .filter(candidate -> ranker.score(candidate, text) >= threshold)
        .toList();
  }

  @Override
  public CompletableFuture<List<TrackCandidate>> resolvePlaylist(UrlQuery query) {
    TrackLoader loader = loaders.get(query.source());
    if (loader == null) {
      return CompletableFuture.completedFuture(List.of());
    }
    // POR QUE: one loader call fetches the whole list; the per-track budget
    // is too short for large playlists.
    return withTimeout(loader.loadPlaylist(query.uri()), resolveTimeout.multipliedBy(4))
        .thenApply(
            candidates -> {
              breaker.recordSuccess(query.source());
              return candidates;
            });
  }

  private CompletableFuture<List<TrackCandidate>> resolveUrl(UrlQuery query) {
    TrackLoader loader = loaders.get(query.source());
    if (loader == null) {
      return CompletableFuture.completedFuture(List.of());
    }
    return loadDirect(query, loader, true);
  }

  /**
   * Direct load with one delayed retry for SoundCloud's transient "no
   * supported formats" error. The first format error never touches the
   * breaker: it is usually a CDN hiccup, not a dead source.
   */
  private CompletableFuture<List<TrackCandidate>> loadDirect(
      UrlQuery query, TrackLoader loader, boolean allowFormatRetry) {
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
              if (allowFormatRetry && isScFormatError(query.source(), failure)) {
                return retryAfterDelay(query, loader);
              }
              breaker.recordFailure(query.source());
              return fallbackSearch(query);
            });
  }

  private CompletableFuture<List<TrackCandidate>> retryAfterDelay(
      UrlQuery query, TrackLoader loader) {
    return CompletableFuture.supplyAsync(
            () -> null,
            CompletableFuture.delayedExecutor(
                scRetryDelay.toMillis(), TimeUnit.MILLISECONDS))
        .thenCompose(ignored -> loadDirect(query, loader, false));
  }

  private static boolean isScFormatError(Source source, Throwable failure) {
    if (source != Source.SOUNDCLOUD) {
      return false;
    }
    Throwable cause =
        failure instanceof java.util.concurrent.CompletionException
            ? failure.getCause()
            : failure;
    return cause instanceof com.sedmelluq.discord.lavaplayer.tools.FriendlyException
        && cause.getMessage() != null
        && cause.getMessage().contains(SC_FORMAT_ERROR);
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
    log.debug("sc-fallback slug={} titleSlug={} uri={}", full, titleOnly, query.uri());
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
    return resolveSearch(text).thenApply(candidates -> rankedAboveThreshold(candidates, text));
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
    int position = 0;
    for (String segment : path.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      String lowered = segment.toLowerCase(java.util.Locale.ROOT);
      // POR QUE: only the third segment carries the unlisted token (s-xxxx);
      // artist slugs like s-club elsewhere are searchable text.
      if (SLUG_STOP_SEGMENTS.contains(lowered)
          || (position == 2 && lowered.startsWith("s-"))) {
        position++;
        continue;
      }
      position++;
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

  /** Text queries blend provider rank into the text score (short-query fix). */
  private CompletableFuture<List<TrackCandidate>> resolveText(String text) {
    if (!parallelSearch) {
      CompletableFuture<List<TrackCandidate>> youtube = searchSource(Source.YOUTUBE, text);
      return youtube.thenCompose(
          candidates -> {
            if (!candidates.isEmpty()) {
              return CompletableFuture.completedFuture(rankedText(candidates, List.of(), text));
            }
            return searchSource(Source.SOUNDCLOUD, text)
                .thenApply(soundcloud -> rankedText(soundcloud, List.of(), text));
          });
    }
    return searchBoth(text)
        .thenApply(lists -> rankedText(lists.youtube(), lists.soundcloud(), text));
  }

  private record SearchLists(List<TrackCandidate> youtube, List<TrackCandidate> soundcloud) {
  }

  private record ScoredEntry(TrackCandidate candidate, int index, double blended) {
  }

  private CompletableFuture<SearchLists> searchBoth(String text) {
    return searchSource(Source.YOUTUBE, text)
        .thenCombine(searchSource(Source.SOUNDCLOUD, text), SearchLists::new);
  }

  private CompletableFuture<List<TrackCandidate>> resolveSearch(String text) {
    if (!parallelSearch) {
      return resolveSearchSequential(text);
    }
    return searchBoth(text).thenApply(lists -> concat(lists.youtube(), lists.soundcloud()));
  }

  /** v1 behavior: YouTube first, SoundCloud only when YouTube has nothing. */
  private CompletableFuture<List<TrackCandidate>> resolveSearchSequential(String text) {
    CompletableFuture<List<TrackCandidate>> youtube = searchSource(Source.YOUTUBE, text);
    return youtube.thenCompose(
        candidates -> {
          if (!candidates.isEmpty()) {
            return CompletableFuture.completedFuture(candidates);
          }
          return searchSource(Source.SOUNDCLOUD, text);
        });
  }

  private static List<TrackCandidate> concat(
      List<TrackCandidate> first, List<TrackCandidate> second) {
    if (first.isEmpty()) {
      return second;
    }
    if (second.isEmpty()) {
      return first;
    }
    List<TrackCandidate> merged = new java.util.ArrayList<>(first.size() + second.size());
    merged.addAll(first);
    merged.addAll(second);
    return merged;
  }

  /** Blended text + provider-rank order, duplicate titles merged. */
  private List<TrackCandidate> rankedText(
      List<TrackCandidate> youtube, List<TrackCandidate> soundcloud, String text) {
    List<ScoredEntry> entries = new java.util.ArrayList<>();
    addEntries(entries, youtube, text);
    addEntries(entries, soundcloud, text);
    java.util.Map<String, ScoredEntry> byKey = new java.util.LinkedHashMap<>();
    for (ScoredEntry entry : entries) {
      String key =
          Ranker.normalize(entry.candidate().title() + " " + entry.candidate().artist());
      byKey.merge(
          key, entry, (left, right) -> right.blended() > left.blended() ? right : left);
    }
    return byKey.values().stream()
        .sorted(
            Comparator.comparingDouble(ScoredEntry::blended)
                .reversed()
                .thenComparingInt(ScoredEntry::index)
                .thenComparingLong(
                    entry ->
                        entry.candidate().duration() == null
                            ? Long.MAX_VALUE
                            : entry.candidate().duration().getSeconds()))
        .map(ScoredEntry::candidate)
        .toList();
  }

  private void addEntries(List<ScoredEntry> entries, List<TrackCandidate> list, String text) {
    for (int i = 0; i < list.size(); i++) {
      TrackCandidate candidate = list.get(i);
      double blended =
          TEXT_WEIGHT * ranker.score(candidate, text) + RANK_WEIGHT * (1.0 / (1 + i));
      entries.add(new ScoredEntry(candidate, i, blended));
    }
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
    return withTimeout(future, resolveTimeout);
  }

  private <T> CompletableFuture<T> withTimeout(
      CompletableFuture<T> future, Duration timeout) {
    return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }
}
