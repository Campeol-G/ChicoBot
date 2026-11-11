package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.chico.bot.source.Ranker;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioRecoveryTest {

  private static TrackCandidate candidate(Source source, String title, String artist) {
    return new TrackCandidate(
        source, title, artist, Duration.ofSeconds(200),
        URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ"), 0.0, null);
  }

  private static FriendlyException failure(FriendlyException.Severity severity) {
    return new FriendlyException("boom", severity, null);
  }

  @Test
  void suspiciousFailureReloadsOnceBeforeFallback() {
    AtomicInteger reloads = new AtomicInteger();
    TrackCandidate failed = candidate(Source.YOUTUBE, "Song", "Artist");
    TrackCandidate reloaded = candidate(Source.YOUTUBE, "Song", "Artist");
    AudioRecovery recovery =
        new AudioRecovery(
            new Ranker(0.35),
            track -> {
              reloads.incrementAndGet();
              return CompletableFuture.completedFuture(reloaded);
            },
            text -> CompletableFuture.completedFuture(List.of()));

    Optional<TrackCandidate> result =
        recovery.recover(failed, failure(FriendlyException.Severity.SUSPICIOUS), false).join();

    assertTrue(result.isPresent());
    assertEquals(1, reloads.get());
  }

  @Test
  void faultFailureSkipsReload() {
    AtomicInteger reloads = new AtomicInteger();
    TrackCandidate failed = candidate(Source.YOUTUBE, "Song", "Artist");
    AudioRecovery recovery =
        new AudioRecovery(
            new Ranker(0.35),
            track -> {
              reloads.incrementAndGet();
              return CompletableFuture.completedFuture(null);
            },
            text -> CompletableFuture.completedFuture(List.of()));

    assertTrue(
        recovery.recover(failed, failure(FriendlyException.Severity.FAULT), false).join().isEmpty());
    assertEquals(0, reloads.get());
  }

  @Test
  void failedReloadFallsBackToGatedSearch() {
    TrackCandidate failed = candidate(Source.YOUTUBE, "Gone Are The Days", "Nujabes");
    TrackCandidate soundcloud = candidate(Source.SOUNDCLOUD, "Gone Are The Days", "Nujabes");
    TrackCandidate youtube = candidate(Source.YOUTUBE, "Gone Are The Days", "Nujabes");
    AudioRecovery recovery =
        new AudioRecovery(
            new Ranker(0.35),
            track -> CompletableFuture.failedFuture(new RuntimeException("reload failed")),
            text -> CompletableFuture.completedFuture(List.of(youtube, soundcloud)));

    Optional<TrackCandidate> result =
        recovery.recover(failed, failure(FriendlyException.Severity.COMMON), false).join();

    assertTrue(result.isPresent());
    assertEquals(Source.SOUNDCLOUD, result.orElseThrow().source());
  }

  @Test
  void alreadyRetriedGoesStraightToFallback() {
    AtomicInteger reloads = new AtomicInteger();
    TrackCandidate failed = candidate(Source.YOUTUBE, "Song", "Artist");
    AudioRecovery recovery =
        new AudioRecovery(
            new Ranker(0.35),
            track -> {
              reloads.incrementAndGet();
              return CompletableFuture.completedFuture(null);
            },
            text -> CompletableFuture.completedFuture(List.of()));

    assertTrue(
        recovery
            .recover(failed, failure(FriendlyException.Severity.COMMON), true)
            .join()
            .isEmpty());
    assertEquals(0, reloads.get());
  }

  @Test
  void fallbackBelowThresholdIsRejected() {
    TrackCandidate failed = candidate(Source.YOUTUBE, "Gone Are The Days", "Nujabes");
    TrackCandidate junk = candidate(Source.YOUTUBE, "Something Else", "Nobody");
    AudioRecovery recovery =
        new AudioRecovery(
            new Ranker(0.9),
            track -> CompletableFuture.completedFuture(null),
            text -> CompletableFuture.completedFuture(List.of(junk)));

    assertFalse(
        recovery
            .recover(failed, failure(FriendlyException.Severity.FAULT), true)
            .join()
            .isPresent());
  }
}
