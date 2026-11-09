package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultTrackResolverTest {

  private static final URI ANY_URI = URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ");

  private static TrackCandidate candidate(Source source, String title, String artist) {
    return new TrackCandidate(
        source, title, artist, Duration.ofSeconds(200), ANY_URI, 0.0, null);
  }

  private static TrackCandidate candidateWithDuration(
      Source source, String title, String artist, long seconds) {
    return new TrackCandidate(
        source, title, artist, Duration.ofSeconds(seconds), ANY_URI, 0.0, null);
  }

  private static final class FakeLoader implements TrackLoader {
    private final Source source;
    final AtomicInteger searches = new AtomicInteger();
    final AtomicInteger urlLoads = new AtomicInteger();
    volatile String lastSearch;
    final java.util.Queue<List<TrackCandidate>> searchQueue = new java.util.ArrayDeque<>();
    volatile List<TrackCandidate> searchResult = List.of();
    volatile Throwable searchFailure;
    volatile boolean searchHangs;
    volatile List<TrackCandidate> urlResult = List.of();
    volatile Throwable urlFailure;

    FakeLoader(Source source) {
      this.source = source;
    }

    @Override
    public Source source() {
      return source;
    }

    @Override
    public CompletableFuture<List<TrackCandidate>> search(String prefixedQuery) {
      searches.incrementAndGet();
      lastSearch = prefixedQuery;
      List<TrackCandidate> queued = searchQueue.poll();
      if (queued != null) {
        return CompletableFuture.completedFuture(queued);
      }
      if (searchHangs) {
        return new CompletableFuture<>();
      }
      if (searchFailure != null) {
        CompletableFuture<List<TrackCandidate>> failed = new CompletableFuture<>();
        failed.completeExceptionally(searchFailure);
        return failed;
      }
      return CompletableFuture.completedFuture(searchResult);
    }

    @Override
    public CompletableFuture<List<TrackCandidate>> loadUrl(URI uri) {
      urlLoads.incrementAndGet();
      if (urlFailure != null) {
        CompletableFuture<List<TrackCandidate>> failed = new CompletableFuture<>();
        failed.completeExceptionally(urlFailure);
        return failed;
      }
      return CompletableFuture.completedFuture(urlResult);
    }
  }

  private static DefaultTrackResolver resolver(
      FakeLoader youtube, FakeLoader soundcloud, Duration timeout, int failureThreshold) {
    Map<Source, TrackLoader> loaders = new EnumMap<>(Source.class);
    loaders.put(Source.YOUTUBE, youtube);
    loaders.put(Source.SOUNDCLOUD, soundcloud);
    return new DefaultTrackResolver(
        loaders,
        new Ranker(0.0),
        new CircuitBreaker(failureThreshold, Duration.ofMinutes(5), Clock.systemUTC()),
        timeout);
  }

  @Test
  void textQuerySearchesYoutubeFirst() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "Azul da Cor do Mar", "Tim Maia"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();

    assertEquals(1, result.size());
    assertEquals("Azul da Cor do Mar", result.get(0).title());
    assertEquals(1, youtube.searches.get());
    assertEquals(0, soundcloud.searches.get());
  }

  @Test
  void youtubeEmptyFallsBackToSoundcloud() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult = List.of();
    soundcloud.searchResult =
        List.of(candidate(Source.SOUNDCLOUD, "Azul da Cor do Mar", "Tim Maia"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();

    assertEquals(1, result.size());
    assertEquals(Source.SOUNDCLOUD, result.get(0).source());
  }

  @Test
  void youtubeFailureFallsBackToSoundcloud() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchFailure = new RuntimeException("boom");
    soundcloud.searchResult =
        List.of(candidate(Source.SOUNDCLOUD, "Azul da Cor do Mar", "Tim Maia"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();

    assertEquals(1, result.size());
    assertEquals(Source.SOUNDCLOUD, result.get(0).source());
  }

  @Test
  void youtubeSkippedAfterThresholdFailures() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchFailure = new RuntimeException("boom");
    soundcloud.searchResult =
        List.of(candidate(Source.SOUNDCLOUD, "Azul da Cor do Mar", "Tim Maia"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 2);

    resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();
    resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();
    List<TrackCandidate> result =
        resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();

    assertEquals(2, youtube.searches.get());
    assertEquals(3, soundcloud.searches.get());
    assertEquals(1, result.size());
  }

  @Test
  void timeoutCountsAsFailure() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchHangs = true;
    soundcloud.searchResult =
        List.of(candidate(Source.SOUNDCLOUD, "Azul da Cor do Mar", "Tim Maia"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofMillis(100), 1);

    List<TrackCandidate> result =
        resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();

    assertEquals(1, result.size());
    assertEquals(Source.SOUNDCLOUD, result.get(0).source());
  }

  @Test
  void urlQueryCallsOnlyItsSource() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlResult =
        List.of(candidate(Source.SOUNDCLOUD, "Some Track", "Some Artist"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver
            .resolve(new UrlQuery(Source.SOUNDCLOUD, URI.create("https://soundcloud.com/a/b")))
            .get();

    assertEquals(1, result.size());
    assertEquals(0, youtube.searches.get());
    assertEquals(0, youtube.urlLoads.get());
    assertEquals(1, soundcloud.urlLoads.get());
  }

  @Test
  void playlistLeakReturnsOnlyFirst() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    TrackCandidate first = candidate(Source.YOUTUBE, "First", "Artist");
    youtube.urlResult =
        List.of(first, candidate(Source.YOUTUBE, "Second", "Artist"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver
            .resolve(new UrlQuery(Source.YOUTUBE, ANY_URI))
            .get();

    assertEquals(List.of(first), result);
  }

  @Test
  void resultsAreRankedBestFirst() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult =
        new ArrayList<>(
            List.of(
                candidate(Source.YOUTUBE, "Blue Swimming Pool", "Someone Else"),
                candidate(Source.YOUTUBE, "Azul da Cor do Mar", "Tim Maia")));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver.resolve(new TextQuery("Tim Maia - Azul da Cor do Mar")).get();

    assertEquals(2, result.size());
    assertEquals("Azul da Cor do Mar", result.get(0).title());
  }

  @Test
  void scShortCodeWithJunkResultsStaysEmpty() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlResult = List.of();
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "pumapjl & SonoTWS - Prata", "pumapjl"));
    DefaultTrackResolver resolver = resolverWithThreshold(youtube, soundcloud, 0.6);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new UrlQuery(Source.SOUNDCLOUD, URI.create("https://on.soundcloud.com/AbCdEf")))
            .get();

    assertTrue(result.isEmpty());
  }

  @Test
  void scUrlEmptyFallsBackToSlugSearch() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlResult = List.of();
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "Ludwig the Holy Blade", "Jachym Jurda"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new UrlQuery(
                    Source.SOUNDCLOUD,
                    URI.create("https://soundcloud.com/jachym-jurda/ludwig-the-holy-blade")))
            .get();

    assertEquals(1, result.size());
    assertEquals("Ludwig the Holy Blade", result.get(0).title());
    assertEquals(1, soundcloud.urlLoads.get());
    assertEquals(1, youtube.searches.get());
    assertTrue(youtube.lastSearch.startsWith("ytsearch:"));
    assertTrue(youtube.lastSearch.contains("ludwig the holy blade"));
  }

  @Test
  void scUrlFailureFallsBackToSlugSearch() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlFailure = new RuntimeException("boom");
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "Ludwig the Holy Blade", "Jachym Jurda"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 10);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new UrlQuery(
                    Source.SOUNDCLOUD,
                    URI.create("https://soundcloud.com/jachym-jurda/ludwig-the-holy-blade")))
            .get();

    assertEquals(1, result.size());
    assertEquals(1, youtube.searches.get());
  }

  @Test
  void scUrlFallsBackToTitleSlugWhenFullSlugMisses() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlFailure = new RuntimeException("boom");
    youtube.searchQueue.add(List.of());
    youtube.searchQueue.add(
        List.of(candidate(Source.YOUTUBE, "Ludwig the Holy Blade", "GeoffPlaysGuitar")));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 10);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new UrlQuery(
                    Source.SOUNDCLOUD,
                    URI.create("https://soundcloud.com/jachym-jurda/ludwig-the-holy-blade")))
            .get();

    assertEquals(1, result.size());
    assertEquals("Ludwig the Holy Blade", result.get(0).title());
    assertEquals(2, youtube.searches.get());
    assertTrue(youtube.lastSearch.startsWith("ytsearch:"));
    assertTrue(!youtube.lastSearch.contains("jachym"));
  }

  @Test
  void scUrlDirectHitDoesNotSearch() throws Exception {    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlResult =
        List.of(candidate(Source.SOUNDCLOUD, "Direct", "Artist"));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new UrlQuery(
                    Source.SOUNDCLOUD,
                    URI.create("https://soundcloud.com/some-artist/some-track")))
            .get();

    assertEquals(1, result.size());
    assertEquals("Direct", result.get(0).title());
    assertEquals(0, youtube.searches.get());
    assertEquals(0, soundcloud.searches.get());
  }

  @Test
  void slugKeepsSLedArtistSegment() {
    // "s" alone is too short to search (one-char words are dropped), but the
    // artist segment must survive instead of vanishing like an unlisted token.
    assertEquals(
        Optional.of("club live set"),
        DefaultTrackResolver.slugText(URI.create("https://soundcloud.com/s-club/live-set")));
  }

  @Test
  void scUrlWithoutUsableSlugStaysEmpty() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlResult = List.of();
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new UrlQuery(Source.SOUNDCLOUD, URI.create("https://soundcloud.com/likes")))
            .get();

    assertTrue(result.isEmpty());
    assertEquals(0, youtube.searches.get());
    assertEquals(0, soundcloud.searches.get());
  }

  @Test
  void ytUrlEmptyDoesNotSlugSearch() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.urlResult = List.of();
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 3);

    List<TrackCandidate> result = resolver.resolve(new UrlQuery(Source.YOUTUBE, ANY_URI)).get();

    assertTrue(result.isEmpty());
    assertEquals(0, youtube.searches.get());
    assertEquals(0, soundcloud.searches.get());
  }

  private static DefaultTrackResolver resolverWithThreshold(
      FakeLoader youtube, FakeLoader soundcloud, double threshold) {
    Map<Source, TrackLoader> loaders = new EnumMap<>(Source.class);
    loaders.put(Source.YOUTUBE, youtube);
    loaders.put(Source.SOUNDCLOUD, soundcloud);
    return new DefaultTrackResolver(
        loaders,
        new Ranker(threshold),
        new CircuitBreaker(3, Duration.ofMinutes(5), Clock.systemUTC()),
        Duration.ofSeconds(5));
  }

  @Test
  void ocrQueryBelowThresholdYieldsEmpty() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "pumapjl & SonoTWS - Prata", "pumapjl"));
    DefaultTrackResolver resolver = resolverWithThreshold(youtube, soundcloud, 0.6);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new OcrQuery(new dev.chico.bot.parser.ParsedTrack("raw", "Sp.lral", "Nujabes", 90.0)))
            .get();

    assertTrue(result.isEmpty());
    assertEquals(1, youtube.searches.get());
    assertEquals(1, soundcloud.searches.get());
  }

  @Test
  void ocrQueryNearMissPassesAtRecallThreshold() throws Exception {
    // POR QUE: the same pair scores ~0.585, filtered at 0.6 but kept at the
    // recall-first 0.35 default — the user prefers a close match over not-found.
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "pumapjl & SonoTWS - Prata", "pumapjl"));
    soundcloud.searchResult = List.of();
    DefaultTrackResolver resolver = resolverWithThreshold(youtube, soundcloud, 0.35);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new OcrQuery(new dev.chico.bot.parser.ParsedTrack("raw", "Sp.lral", "Nujabes", 90.0)))
            .get();

    assertEquals(1, result.size());
  }

  @Test
  void ocrQueryWithNoCandidatesStaysNotFound() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult = List.of();
    soundcloud.searchResult = List.of();
    DefaultTrackResolver resolver = resolverWithThreshold(youtube, soundcloud, 0.35);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new OcrQuery(new dev.chico.bot.parser.ParsedTrack("raw", "zxqw", "jklp", 90.0)))
            .get();

    assertTrue(result.isEmpty());
  }

  @Test
  void ocrQueryUnrelatedCandidateStillEnqueuesAtRecallThreshold() throws Exception {
    // POR QUE: recall-first per user call 2026-11-09 — at 0.35 even an
    // unrelated candidate scores above the gate, so it enqueues instead of
    // becoming not-found. Pins the accepted precision tradeoff (v1.1 item 14
    // owns real ranking).
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "pumapjl & SonoTWS - Prata", "pumapjl"));
    soundcloud.searchResult = List.of();
    DefaultTrackResolver resolver = resolverWithThreshold(youtube, soundcloud, 0.35);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new OcrQuery(new dev.chico.bot.parser.ParsedTrack("raw", "zxqw", "jklp", 90.0)))
            .get();

    assertEquals(1, result.size());
  }

  @Test
  void ocrQueryAboveThresholdRanksFirst() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult =
        new ArrayList<>(
            List.of(
                candidate(Source.YOUTUBE, "pumapjl & SonoTWS - Prata", "pumapjl"),
                candidate(Source.YOUTUBE, "Gone Are The Days", "Nujabes")));
    DefaultTrackResolver resolver = resolverWithThreshold(youtube, soundcloud, 0.6);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new OcrQuery(
                    new dev.chico.bot.parser.ParsedTrack(
                        "raw", "Gone Are The Days", "Nujabes, Uyama Hiroto", 90.0)))
            .get();

    assertEquals(1, result.size());
    assertEquals("Gone Are The Days", result.get(0).title());
  }

  @Test
  void textQueryIgnoresThreshold() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    youtube.searchResult =
        List.of(candidate(Source.YOUTUBE, "pumapjl & SonoTWS - Prata", "pumapjl"));
    DefaultTrackResolver resolver = resolverWithThreshold(youtube, soundcloud, 0.9);

    List<TrackCandidate> result =
        resolver.resolve(new TextQuery("Sp.lral Nujabes")).get();

    assertEquals(1, result.size());
  }

  @Test
  void scFallbackPrefersShorterWhenTextTies() throws Exception {
    FakeLoader youtube = new FakeLoader(Source.YOUTUBE);
    FakeLoader soundcloud = new FakeLoader(Source.SOUNDCLOUD);
    soundcloud.urlFailure = new RuntimeException("boom");
    youtube.searchResult =
        new ArrayList<>(
            List.of(
                candidateWithDuration(
                    Source.YOUTUBE,
                    "Brasilian Skies",
                    "Masayoshi Takanaka",
                    11 * 60 + 55),
                candidateWithDuration(
                    Source.YOUTUBE, "Brasilian Skies", "Masayoshi Takanaka", 6 * 60 + 5)));
    DefaultTrackResolver resolver =
        resolver(youtube, soundcloud, Duration.ofSeconds(5), 10);

    List<TrackCandidate> result =
        resolver
            .resolve(
                new UrlQuery(
                    Source.SOUNDCLOUD,
                    URI.create("https://soundcloud.com/wild-eagle/brasilian-skies-live-2004")))
            .get();

    assertEquals(2, result.size());
    assertEquals(6 * 60 + 5, result.get(0).duration().getSeconds());
  }
}
