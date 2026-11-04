package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbedsTest {

  private static final URI ANY_URI = URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ");

  private static TrackCandidate candidate(String title, String artist, long seconds) {
    return new TrackCandidate(
        Source.YOUTUBE, title, artist, Duration.ofSeconds(seconds), ANY_URI, 0.0, null);
  }

  @Test
  void nowPlayingEscapesExternalText() {
    var embed = Embeds.nowPlaying(candidate("@everyone listen", "artist", 200));

    assertFalse(embed.getTitle().contains("@everyone"), embed.getTitle());
    assertTrue(embed.getTitle().contains("listen"), embed.getTitle());
  }

  @Test
  void queueIsNumberedFromOne() {
    var embed =
        Embeds.queue(
            List.of(candidate("First", "a", 200), candidate("Second", "b", 200)));

    assertTrue(embed.getDescription().contains("1."), embed.getDescription());
    assertTrue(embed.getDescription().contains("2."), embed.getDescription());
  }

  @Test
  void errorKeepsMessage() {
    var embed = Embeds.error("Nothing found. Try another name or link.");

    assertEquals("Nothing found. Try another name or link.", embed.getDescription());
  }
}
