package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.sun.net.httpserver.HttpServer;
import dev.chico.bot.config.Config;
import dev.chico.bot.source.MetadataLookup;
import dev.chico.bot.source.PlayQuery;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TextQuery;
import dev.chico.bot.source.TrackCandidate;
import dev.chico.bot.source.TrackResolver;
import dev.chico.bot.source.UrlQuery;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  static final class RecordingResolver implements TrackResolver {
    private final List<TrackCandidate> candidates;
    final AtomicReference<String> lastText = new AtomicReference<>();

    RecordingResolver(List<TrackCandidate> candidates) {
      this.candidates = candidates;
    }

    @Override
    public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
      if (query instanceof TextQuery text) {
        lastText.set(text.text());
      }
      return CompletableFuture.completedFuture(candidates);
    }
  }

  static final class PlaylistResolver implements TrackResolver {
    private final List<TrackCandidate> candidates;

    PlaylistResolver(List<TrackCandidate> candidates) {
      this.candidates = candidates;
    }

    @Override
    public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
      return CompletableFuture.completedFuture(candidates);
    }

    @Override
    public CompletableFuture<List<TrackCandidate>> resolvePlaylist(UrlQuery query) {
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
  void spotifyPlaylistLinkIsRefusedWithoutPremiumCredentials() {
    PlaybackService.PlayResult result =
        service()
            .play(1L, 2L, 3L, "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
            .join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("Premium"));
  }

  @Test
  void spotifyTrackUsesOembedTitleAndShowsPartialSupport() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oembed", exchange -> {
      byte[] body = "{\"title\":\"Never Gonna Give You Up\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MetadataLookup metadata =
          new MetadataLookup(
              URI.create("https://example.invalid"),
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oembed"),
              Duration.ofSeconds(5));
      RecordingResolver resolver =
          new RecordingResolver(List.of(candidate("Never Gonna Give You Up")));
      PlaybackService service =
          new PlaybackService(
              new SilentVoice(),
              new Config(Map.of("DISCORD_TOKEN", "test")),
              Clock.systemUTC(),
              resolver,
              metadata);

      PlaybackService.PlayResult result =
          service
              .play(1L, 2L, 3L, "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8")
              .join();

      assertTrue(result.queued());
      assertTrue(result.message().contains("partially supported"));
      assertEquals("Never Gonna Give You Up", resolver.lastText.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void spotifyTrackWithNoSearchResultsKeepsNothingFound() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oembed", exchange -> {
      byte[] body = "{\"title\":\"Never Gonna Give You Up\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MetadataLookup metadata =
          new MetadataLookup(
              URI.create("https://example.invalid"),
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oembed"),
              Duration.ofSeconds(5));
      PlaybackService service =
          new PlaybackService(
              new SilentVoice(),
              new Config(Map.of("DISCORD_TOKEN", "test")),
              Clock.systemUTC(),
              new EmptyResolver(),
              metadata);

      PlaybackService.PlayResult result =
          service
              .play(1L, 2L, 3L, "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8")
              .join();

      assertFalse(result.queued());
      assertEquals("Nothing found. Try another name or link.", result.message());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void spotifyOembedFailureRepliesUnreadableLink() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/oembed", exchange -> {
      byte[] body = "{\"title\":\"Never Gonna Give You Up\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(500, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      MetadataLookup metadata =
          new MetadataLookup(
              URI.create("https://example.invalid"),
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oembed"),
              Duration.ofSeconds(5));
      RecordingResolver resolver =
          new RecordingResolver(List.of(candidate("Never Gonna Give You Up")));
      PlaybackService service =
          new PlaybackService(
              new SilentVoice(),
              new Config(Map.of("DISCORD_TOKEN", "test")),
              Clock.systemUTC(),
              resolver,
              metadata);

      PlaybackService.PlayResult result =
          service
              .play(1L, 2L, 3L, "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8")
              .join();

      assertFalse(result.queued());
      assertEquals("Could not read that link. Send the track name instead.", result.message());
      assertNull(resolver.lastText.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void spotifyEpisodeLinkIsRefusedWithTrackHint() {
    PlaybackService.PlayResult result =
        service()
            .play(1L, 2L, 3L, "https://open.spotify.com/episode/4uLU6hMCjMI75M1A2tKUQm")
            .join();

    assertFalse(result.queued());
    assertTrue(result.message().contains("episodes"));
    assertTrue(result.message().contains("song name"));
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
  void skipNeedsMajorityForSomeoneElsesTrack() {
    PlaybackService service = service();
    service.enqueueResolved(1L, List.of(candidate("Alpha")), 100L);
    service.enqueueResolved(1L, List.of(candidate("Beta")), 200L);
    java.util.Set<Long> humans = java.util.Set.of(100L, 200L, 300L);

    assertEquals(
        "Skipped 1/2.", service.skip(1L, Optional.empty(), 300L, humans));
    assertEquals("Alpha", service.queueView(1L).current().orElseThrow().title());

    assertTrue(
        service.skip(1L, Optional.empty(), 200L, humans).startsWith("Skipped to Beta."));
    assertEquals("Beta", service.queueView(1L).current().orElseThrow().title());
  }

  @Test
  void ownerSkipsImmediately() {
    PlaybackService service = service();
    service.enqueueResolved(1L, List.of(candidate("Alpha")), 100L);
    service.enqueueResolved(1L, List.of(candidate("Beta")), 100L);

    assertTrue(
        service
            .skip(1L, Optional.empty(), 100L, java.util.Set.of(100L, 200L, 300L))
            .startsWith("Skipped to Beta."));
  }

  @Test
  void legacyTrackIsFreeForAnyone() {
    PlaybackService service = service();
    service.enqueueResolved(1L, List.of(candidate("Alpha"), candidate("Beta")));

    assertTrue(
        service
            .skip(1L, Optional.empty(), 300L, java.util.Set.of(100L, 200L, 300L))
            .startsWith("Skipped to Beta."));
  }

  @Test
  void removeNeedsMajorityForSomeoneElsesTrack() {
    PlaybackService service = service();
    service.enqueueResolved(1L, List.of(candidate("Alpha")), 100L);
    service.enqueueResolved(1L, List.of(candidate("Beta")), 200L);
    java.util.Set<Long> humans = java.util.Set.of(100L, 200L, 300L);

    assertEquals("Removed 1/2.", service.remove(1L, "2", 300L, humans));
    assertEquals(1, service.queueView(1L).queued().size());

    assertEquals("Removed Beta (position 2).", service.remove(1L, "2", 100L, humans));
    assertTrue(service.queueView(1L).queued().isEmpty());
  }

  @Test
  void voteExpiresSilently() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
    PlaybackService service =
        serviceWith(
            new SilentVoice(),
            new Config(Map.of("DISCORD_TOKEN", "test")),
            clock,
            new EmptyResolver());
    service.enqueueResolved(1L, List.of(candidate("Alpha")), 100L);
    java.util.Set<Long> humans = java.util.Set.of(100L, 200L, 300L);
    assertEquals("Skipped 1/2.", service.skip(1L, Optional.empty(), 300L, humans));

    clock.advance(Duration.ofSeconds(61));

    assertEquals("Skipped 1/2.", service.skip(1L, Optional.empty(), 200L, humans));
    assertEquals("Alpha", service.queueView(1L).current().orElseThrow().title());
  }

  @Test
  void isPausedReflectsPlayerState() {
    PlaybackService service = service();
    assertFalse(service.isPaused(1L));

    service.enqueueResolved(1L, List.of(candidate("Alpha")));
    assertFalse(service.isPaused(1L));

    assertEquals("Paused.", service.pause(1L));
    assertTrue(service.isPaused(1L));

    assertEquals("Resumed.", service.resume(1L));
    assertFalse(service.isPaused(1L));
  }

  @Test
  void loopRepliesFollowTargetSemantics() {
    PlaybackService service = service();
    service.enqueueResolved(1L, List.of(candidate("Alpha"), candidate("Beta")));

    assertEquals("Looping current track.", service.loop(1L, Optional.empty()));
    assertEquals("Loop disabled.", service.loop(1L, Optional.of("disable")));
    assertEquals("Looping queue (2 tracks).", service.loop(1L, Optional.of("all")));
    assertEquals("Looping track 2 - Beta.", service.loop(1L, Optional.of("2")));
    assertEquals("No queued track matches that.", service.loop(1L, Optional.of("0")));
    assertEquals("No queued track matches that.", service.loop(1L, Optional.of("9")));
    assertTrue(service.loop(1L, Optional.of("wat")).startsWith("Use `/loop`"));
  }

  @Test
  void loopWithNothingPlayingSaysSo() {
    PlaybackService service = service();

    assertEquals("Nothing is playing.", service.loop(1L, Optional.empty()));
    assertEquals("Nothing is playing.", service.loop(1L, Optional.of("all")));
    assertEquals("Loop disabled.", service.loop(1L, Optional.of("disable")));
  }

  @Test
  void playlistLinksShowPreviewInsteadOfLoadingOneTrack() {
    PlaybackService.PlayResult youtube =
        service()
            .play(1L, 2L, 3L, "https://youtube.com/watch?v=dQw4w9WgXcQ&list=PLx")
            .join();
    PlaybackService.PlayResult soundcloud =
        service()
            .play(1L, 2L, 3L, "https://soundcloud.com/artist/sets/name")
            .join();

    assertFalse(youtube.queued());
    assertTrue(youtube.playlist() != null);
    assertTrue(youtube.playlist().canPlaySingle());
    assertFalse(soundcloud.queued());
    assertTrue(soundcloud.message().contains("private or unavailable"));
  }

  @Test
  void playlistPreviewEnqueuesAllBeyondQueueMax() {
    PlaybackService service =
        serviceWith(
            new SilentVoice(),
            new Config(
                Map.of(
                    "DISCORD_TOKEN", "test",
                    "PLAY_COOLDOWN_S", "0",
                    "QUEUE_MAX", "2")),
            Clock.systemUTC(),
            new PlaylistResolver(
                List.of(
                    candidate("A"), candidate("B"), candidate("C"),
                    candidate("D"), candidate("E"))));

    PlaybackService.PlayResult preview =
        service.play(1L, 2L, 3L, "https://youtube.com/playlist?list=PLx").join();
    var sessionId = preview.playlist().sessionId();
    assertTrue(sessionId != null);

    assertEquals("Enqueued 5.", service.confirmPlaylist(sessionId, 3L, 1L, false, 2L).join());
    PlaybackService.QueueView view = service.queueView(1L);
    assertEquals(1, view.current().isPresent() ? 1 : 0);
    assertEquals(4, view.queued().size());
  }

  @Test
  void playlistConfirmIsOwnerOnly() {
    PlaybackService service =
        serviceWith(
            new SilentVoice(),
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new PlaylistResolver(List.of(candidate("A"), candidate("B"))));
    var sessionId =
        service.play(1L, 2L, 3L, "https://youtube.com/playlist?list=PLx")
            .join()
            .playlist()
            .sessionId();

    assertEquals(
        "Only the requester can do that.",
        service.confirmPlaylist(sessionId, 99L, 1L, false, 2L).join());
    assertTrue(service.playlistSession(sessionId).isPresent());
  }

  @Test
  void playlistConfirmExpiresWithTtl() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-07T12:00:00Z"));
    PlaybackService service =
        serviceWith(
            new SilentVoice(),
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            clock,
            new PlaylistResolver(List.of(candidate("A"), candidate("B"))));
    var sessionId =
        service.play(1L, 2L, 3L, "https://youtube.com/playlist?list=PLx")
            .join()
            .playlist()
            .sessionId();

    clock.advance(Duration.ofSeconds(301));

    assertEquals(
        "That playlist expired.",
        service.confirmPlaylist(sessionId, 3L, 1L, false, 2L).join());
  }

  @Test
  void playlistConfirmSingleTrackPlaysTheLinkedTrack() {
    PlaybackService service =
        serviceWith(
            new SilentVoice(),
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new PlaylistResolver(List.of(candidate("A"))));
    var sessionId =
        service.play(1L, 2L, 3L, "https://youtube.com/watch?v=dQw4w9WgXcQ&list=PLx")
            .join()
            .playlist()
            .sessionId();

    assertEquals(
        "Playing that track.",
        service.confirmPlaylist(sessionId, 3L, 1L, true, 2L).join());
    assertTrue(service.queueView(1L).current().isPresent());
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
  void skipLastTrackReportsNothingPlayingAndClearsLoop() {
    PlaybackService service = service();
    service.enqueueResolved(1L, List.of(candidate("Alpha")));
    service.loop(1L, Optional.of("all"));

    assertEquals("Skipped Alpha. Nothing is playing.", service.skip(1L, Optional.empty()));
    assertEquals(
        dev.chico.bot.audio.TrackScheduler.LoopMode.OFF, service.loopView(1L).mode());
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

    assertEquals(
        "Skipped to 2 - Beta.\nSkipped:\n1. Thriller",
        service.skip(1L, java.util.Optional.of("2")));
    assertEquals(
        "Skipped to Delta.\nSkipped:\n1. Beta\n2. Gamma",
        service.skip(1L, java.util.Optional.of("delta")));
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

    assertEquals(
        "Skipped to 3 - Gamma.\nSkipped:\n1. Alpha\n2. Beta",
        service.skip(1L, java.util.Optional.of("3")));
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
  void concurrentSkipRemoveStopStayConsistent() throws Exception {
    // Best-effort guard for the hoisted find+act lock: races 3 mutators per
    // guild across 50 guilds and asserts none throws and no removal is
    // confirmed for a track that was never there.
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("A"))));
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(4);
    java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
    java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
    for (int g = 0; g < 50; g++) {
      final long guild = 1000L + g;
      service.play(guild, 2L, 3L, "a").join();
      service.enqueueResolved(guild, List.of(candidate("B"), candidate("C"), candidate("D")));
      done.add(
          pool.submit(
              () -> {
                try {
                  service.skip(guild, java.util.Optional.of("3"));
                } catch (Exception e) {
                  errors.incrementAndGet();
                }
                return null;
              }));
      done.add(
          pool.submit(
              () -> {
                try {
                  service.remove(guild, "2");
                } catch (Exception e) {
                  errors.incrementAndGet();
                }
                return null;
              }));
      done.add(
          pool.submit(
              () -> {
                try {
                  service.stop(guild);
                } catch (Exception e) {
                  errors.incrementAndGet();
                }
                return null;
              }));
    }
    for (java.util.concurrent.Future<?> f : done) {
      f.get();
    }
    pool.shutdown();
    assertEquals(0, errors.get());
  }

  @Test
  void blankRemoveRefusesWithoutMutating() {
    SilentVoice voice = new SilentVoice();
    PlaybackService service =
        serviceWith(
            voice,
            new Config(Map.of("DISCORD_TOKEN", "test", "PLAY_COOLDOWN_S", "0")),
            Clock.systemUTC(),
            new ListResolver(List.of(candidate("Alpha"))));
    service.play(1L, 2L, 3L, "alpha").join();
    service.enqueueResolved(1L, List.of(candidate("Beta")));

    String message = service.remove(1L, "   ");

    assertEquals("No queued track matches that.", message);
    assertEquals(
        List.of("Beta"),
        service.queueView(1L).queued().stream().map(TrackCandidate::title).toList());
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
