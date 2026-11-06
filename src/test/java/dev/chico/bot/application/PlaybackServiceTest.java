package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chico.bot.config.Config;
import dev.chico.bot.source.PlayQuery;
import dev.chico.bot.source.TrackCandidate;
import dev.chico.bot.source.TrackResolver;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PlaybackServiceTest {

  static final class SilentVoice implements VoiceConnector {
    @Override
    public void connect(long guildId, long voiceChannelId) {
    }

    @Override
    public void disconnect(long guildId) {
    }
  }

  static final class EmptyResolver implements TrackResolver {
    @Override
    public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private static PlaybackService service() {
    return new PlaybackService(
        new SilentVoice(),
        new Config(Map.of("DISCORD_TOKEN", "test")),
        Clock.systemUTC(),
        new EmptyResolver());
  }

  @Test
  void scUrlWithNoResultsReportsSoundCloudMessage() {
    PlaybackService.PlayResult result =
        service()
            .play(1L, 2L, 3L, "https://soundcloud.com/some-artist/some-track")
            .join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("SoundCloud"));
  }

  @Test
  void ytUrlWithNoResultsKeepsGenericMessage() {
    PlaybackService.PlayResult result =
        service()
            .play(1L, 2L, 3L, "https://youtube.com/watch?v=dQw4w9WgXcQ")
            .join();

    assertFalse(result.queued());
    assertEquals("Nothing found. Try another name or link.", result.message());
  }
}
