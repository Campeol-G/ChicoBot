package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrackUrlPolicyTest {

  private static void assertAccepted(String input, Source expected) {
    UrlDecision decision = TrackUrlPolicy.classify(input);
    assertInstanceOf(UrlDecision.Accepted.class, decision, "expected Accepted for " + input);
    UrlDecision.Accepted accepted = (UrlDecision.Accepted) decision;
    assertEquals(expected, accepted.source());
    assertTrue(accepted.uri().toString().startsWith("https://"));
  }

  private static UrlDecision.Accepted accepted(String input) {
    UrlDecision decision = TrackUrlPolicy.classify(input);
    assertInstanceOf(UrlDecision.Accepted.class, decision, "expected Accepted for " + input);
    return (UrlDecision.Accepted) decision;
  }

  private static void assertRejected(String input, UrlDecision.Reason expected) {
    UrlDecision decision = TrackUrlPolicy.classify(input);
    assertInstanceOf(UrlDecision.Rejected.class, decision, "expected Rejected for " + input);
    assertEquals(expected, ((UrlDecision.Rejected) decision).reason());
  }

  @Test
  void youtubeWatchVariantsAccepted() {
    assertAccepted("https://youtube.com/watch?v=dQw4w9WgXcQ", Source.YOUTUBE);
    assertAccepted("https://www.youtube.com/watch?v=dQw4w9WgXcQ", Source.YOUTUBE);
    assertAccepted("https://m.youtube.com/watch?v=dQw4w9WgXcQ", Source.YOUTUBE);
    assertAccepted("https://music.youtube.com/watch?v=dQw4w9WgXcQ", Source.YOUTUBE);
  }

  @Test
  void youtubeShortLinkAccepted() {
    assertAccepted("https://youtu.be/dQw4w9WgXcQ", Source.YOUTUBE);
  }

  @Test
  void youtubeWatchWithExtraParamsAccepted() {
    assertAccepted("https://youtube.com/watch?v=dQw4w9WgXcQ&t=42s", Source.YOUTUBE);
  }

  @Test
  void youtubeHostIsCaseInsensitive() {
    assertAccepted("https://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ", Source.YOUTUBE);
  }

  @Test
  void youtubeExplicitPort443Accepted() {
    assertAccepted("https://youtube.com:443/watch?v=dQw4w9WgXcQ", Source.YOUTUBE);
  }

  @Test
  void youtubeWatchWithPlaylistParamAcceptedWithTrackFallback() {
    UrlDecision.Accepted accepted =
        accepted("https://youtube.com/watch?v=dQw4w9WgXcQ&list=PLxxxx&start_radio=1&si=abc");
    assertTrue(accepted.playlist());
    assertEquals(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", accepted.singleTrack().toString());
    assertEquals(
        "https://www.youtube.com/playlist?list=PLxxxx", accepted.uri().toString());
  }

  @Test
  void youtubePlaylistPageAccepted() {
    UrlDecision.Accepted accepted =
        accepted("https://youtube.com/playlist?list=PLxxxx");
    assertTrue(accepted.playlist());
    assertEquals(null, accepted.singleTrack());
  }

  @Test
  void youtubeShortLinkWithPlaylistParamAcceptedWithTrackFallback() {
    UrlDecision.Accepted accepted =
        accepted("https://youtu.be/dQw4w9WgXcQ?list=PLxxxx&si=abc");
    assertTrue(accepted.playlist());
    assertEquals(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", accepted.singleTrack().toString());
  }

  @Test
  void youtubeWatchStripsUnsupportedParamsAndKeepsStartTime() {
    UrlDecision.Accepted accepted =
        accepted("https://music.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&index=3&si=x");
    assertEquals(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s", accepted.uri().toString());
    assertEquals(null, accepted.singleTrack());
  }

  @Test
  void youtubeShortLinkCanonicalized() {
    UrlDecision.Accepted accepted =
        accepted("https://youtu.be/dQw4w9WgXcQ?si=tracking");
    assertEquals(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ", accepted.uri().toString());
  }

  @Test
  void youtubeChannelRejected() {
    assertRejected("https://youtube.com/@canal", UrlDecision.Reason.UNSUPPORTED_LINK);
    assertRejected(
        "https://youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
        UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void youtubeSearchPageRejected() {
    assertRejected(
        "https://youtube.com/results?search_query=x", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void youtubeShortVideoIdRejected() {
    assertRejected(
        "https://youtube.com/watch?v=dQw4w9WgXc", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void youtubeLongVideoIdRejected() {
    assertRejected(
        "https://youtube.com/watch?v=dQw4w9WgXcQz", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void youtubeWatchWithoutVideoIdRejected() {
    assertRejected("https://youtube.com/watch", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void youtubeShortLinkWithExtraPathRejected() {
    assertRejected(
        "https://youtu.be/dQw4w9WgXcQ/extra", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void soundcloudTrackAccepted() {
    assertAccepted("https://soundcloud.com/artist/track", Source.SOUNDCLOUD);
    assertAccepted("https://www.soundcloud.com/artist/track", Source.SOUNDCLOUD);
  }

  @Test
  void soundcloudUnlistedTrackAccepted() {
    assertAccepted(
        "https://soundcloud.com/artist/track/s-AbC123", Source.SOUNDCLOUD);
  }

  @Test
  void soundcloudShortLinkAccepted() {
    assertAccepted("https://on.soundcloud.com/AbCdEf", Source.SOUNDCLOUD);
  }

  @Test
  void soundcloudSetAcceptedAsPlaylist() {
    UrlDecision.Accepted accepted =
        accepted("https://soundcloud.com/artist/sets/name");
    assertTrue(accepted.playlist());
  }

  @Test
  void soundcloudSectionsRejected() {
    assertRejected(
        "https://soundcloud.com/artist/likes", UrlDecision.Reason.UNSUPPORTED_LINK);
    assertRejected(
        "https://soundcloud.com/artist/tracks", UrlDecision.Reason.UNSUPPORTED_LINK);
    assertRejected(
        "https://soundcloud.com/artist/albums", UrlDecision.Reason.UNSUPPORTED_LINK);
    assertRejected(
        "https://soundcloud.com/artist/reposts", UrlDecision.Reason.UNSUPPORTED_LINK);
    assertRejected(
        "https://soundcloud.com/artist/popular-tracks",
        UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void soundcloudProfileRejected() {
    assertRejected("https://soundcloud.com/artist", UrlDecision.Reason.UNSUPPORTED_LINK);
    assertRejected("https://soundcloud.com/", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void soundcloudShortLinkWithExtraPathRejected() {
    assertRejected(
        "https://on.soundcloud.com/AbCdEf/extra", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void spotifyTrackAcceptedAsMetadataSource() {
    UrlDecision.Accepted accepted =
        accepted("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQm");
    assertFalse(accepted.playlist());
    assertEquals(
        "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQm", accepted.uri().toString());
  }

  @Test
  void spotifyEpisodeStillRejectedWithSpotifyReason() {
    assertRejected(
        "https://open.spotify.com/episode/4uLU6hMCjMI75M1A2tKUQm", UrlDecision.Reason.SPOTIFY);
  }

  @Test
  void spotifyLocalePrefixTrackAcceptedAndCanonicalized() {
    UrlDecision.Accepted accepted =
        accepted("https://open.spotify.com/intl-pt/track/4R3KyW2k2rNfSWo1wrwqKI?si=abc");
    assertEquals(
        "https://open.spotify.com/track/4R3KyW2k2rNfSWo1wrwqKI", accepted.uri().toString());
  }

  @Test
  void spotifyLocalePrefixCollectionsStayRejected() {
    assertRejected(
        "https://open.spotify.com/intl-pt/album/1MgcKezYb874hGsZgYYAdV?si=x",
        UrlDecision.Reason.PLAYLIST);
  }

  @Test
  void spotifyShortLinkAcceptedForMetadataResolution() {
    UrlDecision.Accepted accepted = accepted("https://spotify.link/abc123");
    assertEquals(Source.SPOTIFY, accepted.source());
  }

  @Test
  void spotifyPlaylistAcceptedAsPlaylist() {
    UrlDecision.Accepted accepted =
        accepted("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x");
    assertTrue(accepted.playlist());
    assertEquals(
        "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
        accepted.uri().toString());
  }

  @Test
  void spotifyCollectionsOutsideScopeStayRejected() {
    assertRejected(
        "https://open.spotify.com/album/52bcS8R0L0JKVxQb6MZFmW",
        UrlDecision.Reason.PLAYLIST);
    assertRejected(
        "https://open.spotify.com/show/4uLU6hMCjMI75M1A2tKUQm",
        UrlDecision.Reason.PLAYLIST);
  }

  @Test
  void spotifyProfilesRejected() {
    assertRejected(
        "https://open.spotify.com/artist/4uLU6hMCjMI75M1A2tKUQm",
        UrlDecision.Reason.UNSUPPORTED_LINK);
    assertRejected(
        "https://open.spotify.com/user/someone", UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void appleMusicSongAcceptedAsMetadataSource() {
    assertAccepted(
        "https://music.apple.com/br/album/never-gonna-give-you-up/1559523357?i=1559523359",
        Source.APPLE_MUSIC);
    assertAccepted(
        "https://music.apple.com/us/song/never-gonna-give-you-up/1559523359",
        Source.APPLE_MUSIC);
  }

  @Test
  void appleMusicCollectionsRejectedAsPlaylist() {
    assertRejected(
        "https://music.apple.com/br/album/whenever-you-need-somebody/1559523357",
        UrlDecision.Reason.PLAYLIST);
    assertRejected(
        "https://music.apple.com/br/playlist/topsify/pl.1234567890abcdef",
        UrlDecision.Reason.PLAYLIST);
  }

  @Test
  void appleMusicArtistRejected() {
    assertRejected(
        "https://music.apple.com/br/artist/rick-astley/384236",
        UrlDecision.Reason.UNSUPPORTED_LINK);
  }

  @Test
  void httpRejected() {
    assertRejected(
        "http://youtube.com/watch?v=dQw4w9WgXcQ", UrlDecision.Reason.NOT_HTTPS);
  }

  @Test
  void urlWithCredentialsRejectedAsInvalid() {
    assertRejected(
        "https://user:pw@youtube.com/watch?v=dQw4w9WgXcQ", UrlDecision.Reason.INVALID);
  }

  @Test
  void urlWithCustomPortRejectedAsInvalid() {
    assertRejected(
        "https://youtube.com:8443/watch?v=dQw4w9WgXcQ", UrlDecision.Reason.INVALID);
  }

  @Test
  void unknownHostRejected() {
    assertRejected("https://example.com/x", UrlDecision.Reason.UNKNOWN_HOST);
  }

  @Test
  void plainTextIsInvalid() {
    assertRejected("not a url", UrlDecision.Reason.INVALID);
  }

  @Test
  void blankInputIsInvalid() {
    assertRejected("", UrlDecision.Reason.INVALID);
    assertRejected("   ", UrlDecision.Reason.INVALID);
  }
}
