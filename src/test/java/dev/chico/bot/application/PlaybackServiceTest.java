package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import dev.chico.bot.config.Config;
import dev.chico.bot.source.PlayQuery;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import dev.chico.bot.source.TrackResolver;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlaybackServiceTest {

  static final class SilentVoice implements VoiceConnector {
    final AtomicInteger connects = new AtomicInteger();

    @Override
    public boolean connect(long guildId, long voiceChannelId) {
      connects.incrementAndGet();
      return true;
    }

    @Override
    public void disconnect(long guildId) {
    }
  }

  static final class RefusingVoice implements VoiceConnector {
    @Override
    public boolean connect(long guildId, long voiceChannelId) {
      return false;
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

  static final class ListResolver implements TrackResolver {
    private final List<TrackCandidate> candidates;

    ListResolver(List<TrackCandidate> candidates) {
      this.candidates = candidates;
    }

    @Override
    public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
      return CompletableFuture.completedFuture(candidates);
    }
  }

  static final class FailingResolver implements TrackResolver {
    @Override
    public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
      CompletableFuture<List<TrackCandidate>> failed = new CompletableFuture<>();
      failed.completeExceptionally(new RuntimeException("boom"));
      return failed;
    }
  }

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration step) {
      now = now.plus(step);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final class StubTrack implements InternalAudioTrack {
    private final AudioTrackInfo info;

    StubTrack(String title) {
      this.info =
          new AudioTrackInfo(title, "artist", 200_000L, title, false, "https://youtube.com/watch");
    }

    @Override
    public AudioTrackInfo getInfo() {
      return info;
    }

    @Override
    public String getIdentifier() {
      return info.identifier;
    }

    @Override
    public AudioTrackState getState() {
      return AudioTrackState.INACTIVE;
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isSeekable() {
      return false;
    }

    @Override
    public long getPosition() {
      return 0;
    }

    @Override
    public void setPosition(long position) {
    }

    @Override
    public void setMarker(TrackMarker marker) {
    }

    @Override
    public void addMarker(TrackMarker marker) {
    }

    @Override
    public void removeMarker(TrackMarker marker) {
    }

    @Override
    public long getDuration() {
      return info.length;
    }

    @Override
    public AudioTrack makeClone() {
      return new StubTrack(info.title);
    }

    @Override
    public AudioSourceManager getSourceManager() {
      return null;
    }

    @Override
    public void setUserData(Object userData) {
    }

    @Override
    public Object getUserData() {
      return null;
    }

    @Override
    public <T> T getUserData(Class<T> klass) {
      return null;
    }

    @Override
    public void assignExecutor(AudioTrackExecutor executor, boolean applyPrimordialState) {
    }

    @Override
    public AudioTrackExecutor getActiveExecutor() {
      return null;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) {
    }

    @Override
    public AudioTrackExecutor createLocalExecutor(AudioPlayerManager manager) {
      return null;
    }

    @Override
    public AudioFrame provide() {
      return null;
    }

    @Override
    public AudioFrame provide(long timeout, java.util.concurrent.TimeUnit unit) {
      return null;
    }

    @Override
    public boolean provide(MutableAudioFrame target) {
      return false;
    }

    @Override
    public boolean provide(MutableAudioFrame target, long timeout, java.util.concurrent.TimeUnit unit) {
      return false;
    }
  }

  private static TrackCandidate candidate(String title) {
    return new TrackCandidate(
        Source.YOUTUBE,
        title,
        "artist",
        Duration.ofSeconds(200),
        URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ"),
        0.9,
        new StubTrack(title));
  }

  private static PlaybackService service() {
    return new PlaybackService(
        new SilentVoice(),
        new Config(Map.of("DISCORD_TOKEN", "test")),
        Clock.systemUTC(),
        new EmptyResolver());
  }

  private static PlaybackService serviceWith(
      VoiceConnector voice, Config config, Clock clock, TrackResolver resolver) {
    return new PlaybackService(voice, config, clock, resolver);
  }

  @Test
  void playSpotifyLinkReportsSpotifyUnsupported() {
    PlaybackService.PlayResult result =
        service()
            .play(1L, 2L, 3L, "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQm")
            .join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("Spotify"));
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

  @Test
  void playTextQueuesBestCandidate() {
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Never Gonna Give You Up"))));

    PlaybackService.PlayResult result = service.play(1L, 2L, 3L, "never gonna give you up").join();

    assertTrue(result.queued());
    assertEquals("Never Gonna Give You Up", result.candidate().title());
    assertEquals(1, voice.connects.get());
  }

  @Test
  void soundcloudSetRefusalOmitsYoutubeHint() {
    PlaybackService.PlayResult youtube =
        service()
            .play(1L, 2L, 3L, "https://youtube.com/watch?v=dQw4w9WgXcQ&list=PLx")
            .join();
    PlaybackService.PlayResult soundcloud =
        service()
            .play(1L, 2L, 3L, "https://soundcloud.com/artist/sets/name")
            .join();

    assertTrue(youtube.message().contains("&list="));
    assertTrue(soundcloud.message().contains("Playlists are not supported"));
    assertTrue(!soundcloud.message().contains("&list="));
  }

  @Test
  void playPlaylistUrlRefusesWithHint() {
    PlaybackService.PlayResult result =
        service()
            .play(1L, 2L, 3L, "https://youtube.com/watch?v=dQw4w9WgXcQ&list=PLx")
            .join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("Playlists are not supported"));
  }

  @Test
  void playUnknownHostRefuses() {
    PlaybackService.PlayResult result =
        service().play(1L, 2L, 3L, "https://example.com/track").join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("not from a supported source"));
  }

  @Test
  void playCooldownRejectsSecondCall() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
    PlaybackService service =
        serviceWith(
            new SilentVoice(),
            new Config(Map.of("DISCORD_TOKEN", "test")),
            clock,
            new ListResolver(List.of(candidate("Alpha"))));

    PlaybackService.PlayResult first = service.play(1L, 2L, 3L, "alpha").join();
    PlaybackService.PlayResult second = service.play(1L, 2L, 3L, "beta").join();

    assertTrue(first.queued());
    assertFalse(second.queued());
    assertTrue(second.message().contains("Slow down"));
  }

  @Test
  void playQueueFullReportsFull() {
    // POR QUE: QUEUE_MAX=0 still lets the first track start playing; the
    // queue is only full when the player is busy and no slot remains.
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "QUEUE_MAX", "0", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Alpha"))));

    assertTrue(service.play(1L, 2L, 3L, "alpha").join().queued());
    PlaybackService.PlayResult result = service.play(1L, 2L, 3L, "beta").join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("queue is full"));
  }

  @Test
  void playResolverExceptionReportsSearchFailed() {
    PlaybackService service =
        serviceWith(
            new SilentVoice(),
            new Config(Map.of("DISCORD_TOKEN", "test")),
            Clock.systemUTC(),
            new FailingResolver());

    PlaybackService.PlayResult result = service.play(1L, 2L, 3L, "alpha").join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("Search failed"));
  }

  @Test
  void skipByNumberAndByName() {
    SilentVoice voice = new SilentVoice();
    MutableClock clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            clock,
            new ListResolver(List.of(candidate("Thriller"))));
    // Seed: current=Thriller, queued=[Beta, Gamma, Delta].
    service.play(1L, 2L, 3L, "thriller").join();
    service.enqueueResolved(
        1L, List.of(candidate("Beta"), candidate("Gamma"), candidate("Delta")));

    assertEquals("Skipped.", service.skip(1L, java.util.Optional.of("2")));
    assertEquals("Skipped.", service.skip(1L, java.util.Optional.of("delta")));
  }

  @Test
  void removeByDisplayNumberRemovesShownPosition() {
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Alpha"))));
    service.play(1L, 2L, 3L, "alpha").join();
    service.enqueueResolved(
        1L, List.of(candidate("Beta"), candidate("Gamma"), candidate("Delta")));

    String message = service.remove(1L, "3");

    assertEquals("Removed Gamma (position 3).", message);
    assertEquals(
        List.of("Beta", "Delta"),
        service.queueView(1L).queued().stream().map(TrackCandidate::title).toList());
  }

  @Test
  void skipByDisplayNumberPlaysShownPosition() {
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Alpha"))));
    service.play(1L, 2L, 3L, "alpha").join();
    service.enqueueResolved(
        1L, List.of(candidate("Beta"), candidate("Gamma"), candidate("Delta")));

    assertEquals("Skipped.", service.skip(1L, java.util.Optional.of("3")));
    assertEquals("Gamma", service.queueView(1L).current().orElseThrow().title());
  }

  @Test
  void removeCurrentDisplayNumberRefuses() {
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Alpha"))));
    service.play(1L, 2L, 3L, "alpha").join();
    service.enqueueResolved(1L, List.of(candidate("Beta")));

    String message = service.remove(1L, "1");

    assertTrue(message.contains("current"));
    assertEquals("Alpha", service.queueView(1L).current().orElseThrow().title());
    assertEquals(1, service.queueView(1L).queued().size());
  }

  @Test
  void removeByNumberReportsTitle() {
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Alpha"))));
    service.play(1L, 2L, 3L, "alpha").join();
    service.enqueueResolved(1L, List.of(candidate("Beta")));

    String message = service.remove(1L, "2");

    assertTrue(message.contains("Removed"));
    assertTrue(message.contains("Beta"));
    assertTrue(message.contains("2"));
  }

  @Test
  void playDoesNotEnqueueWhenConnectFails() {
    PlaybackService service =
        serviceWith(
            new RefusingVoice(),
            new Config(Map.of("DISCORD_TOKEN", "test")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Alpha"))));

    PlaybackService.PlayResult result = service.play(1L, 2L, 3L, "alpha").join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("Could not join"));
    assertTrue(service.queueView(1L).queued().isEmpty());
    assertTrue(service.queueView(1L).current().isEmpty());
  }

  @Test
  void pauseResumeStopLeaveOnEmpty() {
    PlaybackService service = service();

    assertEquals("Nothing is playing.", service.pause(77L));
    assertEquals("Nothing to resume.", service.resume(77L));
    assertEquals("Nothing is playing.", service.stop(77L));
    assertTrue(service.queueView(77L).queued().isEmpty());
    assertTrue(service.isIdle(77L));
    assertEquals("Left the voice channel.", service.leave(77L));
  }
}
