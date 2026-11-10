package dev.chico.bot.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accepts track and playlist URL formats from supported sources. Unsupported
 * parameters are stripped instead of refusing the link; non-URLs come back
 * INVALID and go to text search.
 */
public final class TrackUrlPolicy {

  private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
  private static final Pattern LIST_ID = Pattern.compile("[A-Za-z0-9_-]{2,}");
  private static final Pattern SPOTIFY_ID = Pattern.compile("[A-Za-z0-9]{10,}");
  private static final Pattern TIME_PARAM = Pattern.compile("[0-9hms]+");
  private static final Pattern UNLISTED_TOKEN = Pattern.compile("s-.+");

  private static final Set<String> YOUTUBE_HOSTS =
      Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com");
  private static final Set<String> SOUNDCLOUD_HOSTS =
      Set.of("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com");
  private static final Set<String> APPLE_MUSIC_HOSTS =
      Set.of("music.apple.com", "www.music.apple.com");

  private static final Set<String> SOUNDCLOUD_SECTIONS =
      Set.of(
          "likes",
          "tracks",
          "albums",
          "reposts",
          "popular-tracks",
          "followers",
          "following",
          "comments",
          "recommended",
          "stations",
          "groups",
          "people");

  private TrackUrlPolicy() {
  }

  public static UrlDecision classify(String input) {
    if (input == null || input.isBlank()) {
      return rejected(UrlDecision.Reason.INVALID);
    }
    URI uri;
    try {
      uri = new URI(input.strip());
    } catch (URISyntaxException | IllegalArgumentException e) {
      return rejected(UrlDecision.Reason.INVALID);
    }
    if (uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
      return rejected(UrlDecision.Reason.INVALID);
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      return rejected(UrlDecision.Reason.INVALID);
    }
    if (!scheme.equalsIgnoreCase("https")) {
      return rejected(UrlDecision.Reason.NOT_HTTPS);
    }
    String host = uri.getHost();
    if (host == null) {
      return rejected(UrlDecision.Reason.INVALID);
    }
    String lowerHost = host.toLowerCase();
    if (lowerHost.equals("spotify.link")) {
      return spotifyShort(uri);
    }
    if (lowerHost.equals("open.spotify.com")) {
      return spotify(uri);
    }
    if (APPLE_MUSIC_HOSTS.contains(lowerHost)) {
      return appleMusic(uri);
    }
    if (YOUTUBE_HOSTS.contains(lowerHost)) {
      return youtube(uri);
    }
    if (lowerHost.equals("youtu.be")) {
      return youtubeShort(uri);
    }
    if (SOUNDCLOUD_HOSTS.contains(lowerHost)) {
      return soundcloud(uri);
    }
    if (lowerHost.equals("on.soundcloud.com")) {
      return soundcloudShort(uri);
    }
    return rejected(UrlDecision.Reason.UNKNOWN_HOST);
  }

  private static UrlDecision youtube(URI uri) {
    String path = uri.getPath();
    if (path == null) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    Map<String, String> params = queryParams(uri.getQuery());
    if (path.equals("/playlist")) {
      return playlistPage(params);
    }
    if (!path.equals("/watch")) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    String id = params.get("v");
    String list = params.get("list");
    if (id == null || !VIDEO_ID.matcher(id).matches()) {
      // watch?list=... with no video is a playlist link.
      return list != null && !list.isEmpty() ? playlistPage(params)
          : rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    URI single = youtubeWatch(id, params.get("t"));
    if (list != null && LIST_ID.matcher(list).matches()) {
      return new UrlDecision.Accepted(
          Source.YOUTUBE, playlistUri(list), true, single);
    }
    return accepted(Source.YOUTUBE, single);
  }

  private static UrlDecision youtubeShort(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (segments.size() != 1 || !VIDEO_ID.matcher(segments.get(0)).matches()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    URI single = youtubeWatch(segments.get(0), queryParams(uri.getQuery()).get("t"));
    String list = queryParams(uri.getQuery()).get("list");
    if (list != null && LIST_ID.matcher(list).matches()) {
      return new UrlDecision.Accepted(
          Source.YOUTUBE, playlistUri(list), true, single);
    }
    return accepted(Source.YOUTUBE, single);
  }

  /**
   * Keeps only the video id and a valid {@code t} start time. Radio, mix,
   * tracking and index parameters are dropped: they never change the track.
   */
  private static URI youtubeWatch(String id, String time) {
    StringBuilder query = new StringBuilder("v=").append(id);
    if (time != null && !time.isEmpty() && TIME_PARAM.matcher(time).matches()
        && time.chars().anyMatch(Character::isDigit)) {
      query.append("&t=").append(time);
    }
    try {
      return new URI("https", "www.youtube.com", "/watch", query.toString(), null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("validated video id produced a bad URI", e);
    }
  }

  private static URI playlistUri(String list) {
    try {
      return new URI("https", "www.youtube.com", "/playlist", "list=" + list, null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("validated list id produced a bad URI", e);
    }
  }

  private static UrlDecision playlistPage(Map<String, String> params) {
    String list = params.get("list");
    if (list == null || !LIST_ID.matcher(list).matches()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    return new UrlDecision.Accepted(Source.YOUTUBE, playlistUri(list), true, null);
  }

  private static UrlDecision soundcloud(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (segments.isEmpty() || segments.size() > 3) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    if (segments.size() == 1) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    if (segments.get(1).equals("sets") && segments.size() == 3) {
      return new UrlDecision.Accepted(Source.SOUNDCLOUD, canonical(uri), true, null);
    }
    if (segments.size() == 2) {
      if (segments.get(1).equals("sets")) {
        return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
      }
      if (SOUNDCLOUD_SECTIONS.contains(segments.get(1))) {
        return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
      }
      return accepted(Source.SOUNDCLOUD, canonical(uri));
    }
    if (!UNLISTED_TOKEN.matcher(segments.get(2)).matches()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    return accepted(Source.SOUNDCLOUD, canonical(uri));
  }

  private static UrlDecision soundcloudShort(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (segments.size() != 1) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    return accepted(Source.SOUNDCLOUD, uri);
  }

  private static UrlDecision spotify(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (!segments.isEmpty() && isLocalePrefix(segments.get(0))) {
      segments = segments.subList(1, segments.size());
    }
    if (segments.size() < 2 || !SPOTIFY_ID.matcher(segments.get(1)).matches()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    String kind = segments.get(0);
    String id = segments.get(1);
    if (kind.equals("track")) {
      return accepted(Source.SPOTIFY, spotifyUri("track", id));
    }
    if (kind.equals("playlist")) {
      return new UrlDecision.Accepted(Source.SPOTIFY, spotifyUri("playlist", id), true, null);
    }
    return switch (kind) {
      case "episode" -> rejected(UrlDecision.Reason.SPOTIFY);
      case "album", "show" -> rejected(UrlDecision.Reason.PLAYLIST);
      default -> rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    };
  }

  private static boolean isLocalePrefix(String segment) {
    String lowered = segment.toLowerCase(java.util.Locale.ROOT);
    return lowered.matches("intl-[a-z]{2}(-.*)?") || lowered.matches("[a-z]{2}(-[a-z]{2})?");
  }

  private static UrlDecision spotifyShort(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (segments.size() != 1) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    // The content type is behind the redirect; the metadata layer follows it.
    return accepted(Source.SPOTIFY, uri);
  }

  private static URI spotifyUri(String kind, String id) {
    try {
      return new URI("https", "open.spotify.com", "/" + kind + "/" + id, null, null);
    } catch (URISyntaxException e) {
      throw new IllegalStateException("validated Spotify id produced a bad URI", e);
    }
  }

  private static UrlDecision appleMusic(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (segments.isEmpty()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    // Store links carry a country prefix (/br/album/...); shared links may omit it.
    int kind = 0;
    if (segments.size() > 1 && isAppleKind(segments.get(1))) {
      kind = 1;
    }
    return switch (segments.get(kind)) {
      case "song" -> segments.size() > kind + 1 ? accepted(Source.APPLE_MUSIC, uri)
          : rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
      case "album" -> {
        String trackId = queryParams(uri.getQuery()).get("i");
        if (trackId != null && !trackId.isEmpty()) {
          yield accepted(Source.APPLE_MUSIC, uri);
        }
        yield rejected(UrlDecision.Reason.PLAYLIST);
      }
      case "playlist" -> rejected(UrlDecision.Reason.PLAYLIST);
      default -> rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    };
  }

  private static boolean isAppleKind(String segment) {
    return segment.equals("song")
        || segment.equals("album")
        || segment.equals("playlist")
        || segment.equals("artist")
        || segment.equals("music-video");
  }

  private static List<String> pathSegments(String path) {
    if (path == null || path.isBlank()) {
      return List.of();
    }
    return List.of(path.split("/")).stream().filter(s -> !s.isEmpty()).toList();
  }

  private static Map<String, String> queryParams(String query) {
    Map<String, String> params = new HashMap<>();
    if (query == null || query.isEmpty()) {
      return params;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        params.put(pair, "");
      } else {
        params.put(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    return params;
  }

  private static URI canonical(URI uri) {
    try {
      return new URI("https", uri.getHost().toLowerCase(java.util.Locale.ROOT), uri.getPath(),
          null, null);
    } catch (URISyntaxException e) {
      return uri;
    }
  }

  private static UrlDecision accepted(Source source, URI uri) {
    return new UrlDecision.Accepted(source, uri);
  }

  private static UrlDecision rejected(UrlDecision.Reason reason) {
    return new UrlDecision.Rejected(reason);
  }
}
