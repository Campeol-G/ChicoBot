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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultTrackResolverTest {

  private static final URI ANY_URI = URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ");

  private static TrackCandidate candidate(Source source, String title, String artist) {
    return new TrackCandidate(
        source, title, artist, Duration.ofSeconds(200), ANY_URI, 0.0, null);
  }

  private static final class FakeLoader implements TrackLoader {
    private final Source source;
    final AtomicInteger searches = new AtomicInteger();
    final AtomicInteger urlLoads = new AtomicInteger();
    volatile List<TrackCandidate> searchResult = List.of();
    volatile Throwable searchFailure;
    volatile boolean searchHangs;
    volatile List<TrackCandidate> urlResult = List.of();

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
}
