package dev.chico.bot.application;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.chico.bot.source.Ranker;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Playback failure recovery: one fresh reload of the same URI, then a gated
 * SoundCloud-first search, else give up and let the caller skip with a name.
 */
public final class AudioRecovery {

  private final Ranker ranker;
  private final Function<TrackCandidate, CompletableFuture<TrackCandidate>> reloader;
  private final Function<String, CompletableFuture<List<TrackCandidate>>> searcher;

  public AudioRecovery(
      Ranker ranker,
      Function<TrackCandidate, CompletableFuture<TrackCandidate>> reloader,
      Function<String, CompletableFuture<List<TrackCandidate>>> searcher) {
    this.ranker = ranker;
    this.reloader = reloader;
    this.searcher = searcher;
  }

  public CompletableFuture<Optional<TrackCandidate>> recover(
      TrackCandidate failed, FriendlyException failure, int attempt) {
    if (attempt >= 3) {
      // One reload + one fallback per queue item; never loop forever.
      return CompletableFuture.completedFuture(Optional.empty());
    }
    if (attempt <= 1 && retryEligible(failure) && failed.uri() != null) {
      return reloader
          .apply(failed)
          .thenApply(Optional::ofNullable)
          .thenCompose(
              reloaded ->
                  reloaded.isPresent() ? CompletableFuture.completedFuture(reloaded) : fallback(failed))
          .exceptionallyCompose(ignored -> fallback(failed));
    }
    return fallback(failed);
  }

  static boolean retryEligible(FriendlyException failure) {
    // Fail-closed: an unknown severity gets no retry.
    return failure != null
        && failure.severity != null
        && failure.severity != FriendlyException.Severity.FAULT;
  }

  private CompletableFuture<Optional<TrackCandidate>> fallback(TrackCandidate failed) {
    String text = (failed.title() + " " + failed.artist()).strip();
    if (text.isBlank()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    return searcher
        .apply(text)
        .thenApply(candidates -> bestAboveThreshold(candidates, text))
        .exceptionally(failure -> Optional.empty());
  }

  private Optional<TrackCandidate> bestAboveThreshold(
      List<TrackCandidate> candidates, String text) {
    double threshold = ranker.threshold();
    List<TrackCandidate> above =
        candidates.stream()
            .filter(candidate -> ranker.score(candidate, text) >= threshold)
            .toList();
    Optional<TrackCandidate> soundcloud =
        above.stream()
            .filter(candidate -> candidate.source() == Source.SOUNDCLOUD)
            .max(
                java.util.Comparator.comparingDouble(
                    candidate -> ranker.score(candidate, text)));
    if (soundcloud.isPresent()) {
      return soundcloud;
    }
    return above.stream()
        .max(
            java.util.Comparator.comparingDouble(
                candidate -> ranker.score(candidate, text)));
  }
}
