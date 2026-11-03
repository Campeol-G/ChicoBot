package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void youtubeWatchWithPlaylistParamRejected() {
    assertRejected(
        "https://youtube.com/watch?v=dQw4w9WgXcQ&list=PLxxxx",
        UrlDecision.Reason.PLAYLIST);
  }

  @Test
  void youtubePlaylistPageRejected() {
    assertRejected(
        "https://youtube.com/playlist?list=PLxxxx", UrlDecision.Reason.PLAYLIST);
  }

  @Test
  void youtubeShortLinkWithPlaylistParamRejected() {
    assertRejected(
        "https://youtu.be/dQw4w9WgXcQ?list=PLxxxx", UrlDecision.Reason.PLAYLIST);
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
  void soundcloudSetRejectedAsPlaylist() {
    assertRejected(
        "https://soundcloud.com/artist/sets/name", UrlDecision.Reason.PLAYLIST);
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
  void spotifyRejected() {
    assertRejected(
        "https://open.spotify.com/track/xyz", UrlDecision.Reason.SPOTIFY);
    assertRejected("https://spotify.link/abc123", UrlDecision.Reason.SPOTIFY);
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
