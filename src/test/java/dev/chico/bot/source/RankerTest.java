package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RankerTest {

  private static final URI ANY_URI = URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ");

  private static TrackCandidate candidate(String title, String artist, long seconds) {
    return new TrackCandidate(
        Source.YOUTUBE, title, artist, Duration.ofSeconds(seconds), ANY_URI, 0.0, null);
  }

  @Test
  void repeatedWordsDoNotCrashScoring() {
    Ranker ranker = new Ranker(0.35);

    double score =
        ranker.score(candidate("undressed", "sombr", 200), "undressed undressed sombr");

    assertTrue(score >= 0.0 && score <= 1.0, "score stays in range, got " + score);
  }

  @Test
  void exactMatchScoresHigherThanDifferentTrack() {
    Ranker ranker = new Ranker(0.0);
    String target = "Tim Maia - Azul da Cor do Mar";

    double exact = ranker.score(candidate("Azul da Cor do Mar", "Tim Maia", 224), target);
    double other = ranker.score(candidate("Blue Swimming Pool", "Someone Else", 200), target);

    assertTrue(exact > other, "exact match should score higher");
  }

  @Test
  void liveVersionScoresLowerWhenTargetHasNoLive() {
    Ranker ranker = new Ranker(0.0);
    String target = "Eric Clapton - Layla";

    double studio = ranker.score(candidate("Layla", "Eric Clapton", 430), target);
    double live = ranker.score(candidate("Layla (Live)", "Eric Clapton", 430), target);

    assertTrue(studio > live, "studio version should score higher than live");
  }

  @Test
  void noPenaltyWhenTargetAsksForLive() {
    Ranker ranker = new Ranker(0.0);
    TrackCandidate live = candidate("Layla (Live)", "Eric Clapton", 430);

    double wanted = ranker.score(live, "Eric Clapton - Layla (Live)");
    double unwanted = ranker.score(live, "Eric Clapton - Layla");

    assertTrue(wanted > unwanted, "no penalty when the target asks for the live version");
  }

  @Test
  void bestIsEmptyOnlyForEmptyList() {
    Ranker ranker = new Ranker(0.0);

    assertTrue(ranker.best(List.of(), "Tim Maia - Azul da Cor do Mar").isEmpty());
  }

  @Test
  void lyricVideoScoresLowerThanOfficialWhenTargetHasNoLyric() {
    Ranker ranker = new Ranker(0.0);
    String target = "City Lights Nujabes Pase Rock Subs";

    double official =
        ranker.score(
            candidate(
                "Nujabes - City Lights (feat. Pase Rock & Substantial) [Official Audio]",
                "Nujabes",
                200),
            target);
    double lyric =
        ranker.score(
            candidate(
                "City Light - Nujabes ft Pase Rock y Sustantial (Letra en español)", "Hikari", 200),
            target);

    assertTrue(official > lyric, "official audio should score higher than a lyric video");
  }

  @Test
  void bestNeverRefusesByThreshold() {
    Ranker ranker = new Ranker(0.9);
    TrackCandidate terrible = candidate("zzz qqq", "Xyz Abc", 200);

    Optional<TrackCandidate> best =
        ranker.best(List.of(terrible), "Tim Maia - Azul da Cor do Mar");

    assertTrue(best.isPresent(), "v1 never refuses by threshold");
  }

  @Test
  void bestPicksHighestScore() {
    Ranker ranker = new Ranker(0.0);
    TrackCandidate bad = candidate("Blue Swimming Pool", "Someone Else", 200);
    TrackCandidate good = candidate("Azul da Cor do Mar", "Tim Maia", 224);

    Optional<TrackCandidate> best =
        ranker.best(List.of(bad, good), "Tim Maia - Azul da Cor do Mar");

    assertTrue(best.isPresent());
    assertEquals(good, best.orElseThrow());
  }

  @Test
  void shortDurationPenalizedButNotRefused() {
    Ranker ranker = new Ranker(0.0);
    String target = "Tim Maia - Azul da Cor do Mar";

    double normal = ranker.score(candidate("Azul da Cor do Mar", "Tim Maia", 224), target);
    double shortTrack = ranker.score(candidate("Azul da Cor do Mar", "Tim Maia", 20), target);

    assertTrue(normal > shortTrack, "short duration should be penalized");

    Optional<TrackCandidate> best =
        ranker.best(List.of(candidate("Azul da Cor do Mar", "Tim Maia", 20)), target);
    assertTrue(best.isPresent(), "short track is penalized, never refused");
  }

  @Test
  void longDurationPenalizedButNotRefused() {
    Ranker ranker = new Ranker(0.0);
    String target = "Tim Maia - Azul da Cor do Mar";

    double normal = ranker.score(candidate("Azul da Cor do Mar", "Tim Maia", 224), target);
    double longTrack =
        ranker.score(candidate("Azul da Cor do Mar", "Tim Maia", 20 * 60), target);

    assertTrue(normal > longTrack, "long duration should be penalized");

    Optional<TrackCandidate> best =
        ranker.best(List.of(candidate("Azul da Cor do Mar", "Tim Maia", 20 * 60)), target);
    assertTrue(best.isPresent(), "long track is penalized, never refused");
  }
}
