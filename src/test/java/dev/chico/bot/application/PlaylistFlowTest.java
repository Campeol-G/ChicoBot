package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chico.bot.source.PlayQuery;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.SpotifyClient;
import dev.chico.bot.source.TrackCandidate;
import dev.chico.bot.source.TrackResolver;
import dev.chico.bot.source.UrlDecision;
import dev.chico.bot.source.UrlQuery;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PlaylistFlowTest {

  private static PlaylistFlow flow() {
    TrackResolver empty =
        new TrackResolver() {
          @Override
          public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
            return CompletableFuture.completedFuture(List.of());
          }
        };
    return new PlaylistFlow(
        empty,
        Duration.ofMinutes(5),
        Clock.systemUTC());
  }

  @Test
  void playlistFetchFailureIsSearchFailed() {
    PlaylistFlow flow =
        new PlaylistFlow(
            failingResolver(new RuntimeException("timeout")),
            Duration.ofMinutes(5),
            Clock.systemUTC());

    PlaylistPreview preview =
        flow.preview(
                1L,
                2L,
                new UrlDecision.Accepted(
                    Source.YOUTUBE,
                    URI.create("https://youtube.com/playlist?list=PLx"),
                    true,
                    null))
            .join();

    assertNull(preview.sessionId());
    assertEquals("Search failed. Try again later.", preview.error());
  }

  @Test
  void privatePlaylistFailureKeepsPrivateMessage() {
    PlaylistFlow flow =
        new PlaylistFlow(
            failingResolver(
                new com.sedmelluq.discord.lavaplayer.tools.FriendlyException(
                    "This playlist is private",
                    com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON,
                    null)),
            Duration.ofMinutes(5),
            Clock.systemUTC());

    PlaylistPreview preview =
        flow.preview(
                1L,
                2L,
                new UrlDecision.Accepted(
                    Source.YOUTUBE,
                    URI.create("https://youtube.com/playlist?list=PLx"),
                    true,
                    null))
            .join();

    assertNull(preview.sessionId());
    assertEquals("That playlist is private or unavailable.", preview.error());
  }

  @Test
  void spotifyPlaylistContentCreatesSession() {
    PlaylistFlow flow = flow();

    PlaylistPreview preview =
        flow.fromContent(
            1L,
            2L,
            new SpotifyClient.Content(
                SpotifyClient.Kind.PLAYLIST,
                List.of("Artist - One", "Artist - Two"),
                false),
            null);

    assertNull(preview.error());
    assertTrue(preview.sessionId() != null);
    assertEquals(2, preview.items().size());
    assertTrue(flow.get(preview.sessionId()).isPresent());
  }

  @Test
  void spotifyUnavailablePlaylistIsAnError() {
    PlaylistPreview preview =
        flow()
            .fromContent(
                1L,
                2L,
                new SpotifyClient.Content(SpotifyClient.Kind.PLAYLIST, List.of(), true),
                null);

    assertNull(preview.sessionId());
    assertEquals("That playlist is private or unavailable.", preview.error());
  }

  @Test
  void spotifyTrackIsNotAPlaylistPreview() {
    PlaylistPreview preview =
        flow()
            .fromContent(
                1L,
                2L,
                new SpotifyClient.Content(
                    SpotifyClient.Kind.TRACK, List.of("Artist - One"), false),
                null);

    assertNull(preview.sessionId());
    assertTrue(preview.error().contains("Could not read"));
  }

  @Test
  void spotifyTransientPlaylistIsSearchFailed() {
    PlaylistPreview preview =
        flow()
            .fromContent(
                1L,
                2L,
                new SpotifyClient.Content(
                    SpotifyClient.Kind.UNSUPPORTED, List.of(), false, true),
                null);

    assertNull(preview.sessionId());
    assertEquals("Search failed. Try again later.", preview.error());
  }

  @Test
  void spotifyUserRequiredPlaylistKeepsLoginMessage() {
    PlaylistPreview preview =
        flow()
            .fromContent(
                1L,
                2L,
                new SpotifyClient.Content(
                    SpotifyClient.Kind.PLAYLIST,
                    List.of(),
                    false,
                    false,
                    "Spotify playlists need a Spotify login. Run the Spotify login setup and try again."),
                null);

    assertNull(preview.sessionId());
    assertTrue(preview.error().contains("Spotify playlists need a Spotify login"));
  }

  private static TrackResolver failingResolver(Throwable failure) {
    return new TrackResolver() {
      @Override
      public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
        return CompletableFuture.completedFuture(List.of());
      }

      @Override
      public CompletableFuture<List<TrackCandidate>> resolvePlaylist(UrlQuery query) {
        return CompletableFuture.failedFuture(failure);
      }
    };
  }

}
