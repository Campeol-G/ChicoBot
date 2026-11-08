package dev.chico.bot.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accepts only exact single-track URL formats. Anything else is rejected
 * before any request; non-URLs come back INVALID and go to text search.
 */
public final class TrackUrlPolicy {

  private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
  private static final Pattern UNLISTED_TOKEN = Pattern.compile("s-.+");

  private static final Set<String> YOUTUBE_HOSTS =
      Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com");
  private static final Set<String> SOUNDCLOUD_HOSTS =
      Set.of("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com");
  private static final Set<String> SPOTIFY_HOSTS = Set.of("open.spotify.com", "spotify.link");
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
    if (path.equals("/playlist")) {
      return rejected(UrlDecision.Reason.PLAYLIST);
    }
    if (!path.equals("/watch")) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    Map<String, String> params = queryParams(uri.getQuery());
    if (params.containsKey("list")) {
      return rejected(UrlDecision.Reason.PLAYLIST);
    }
    String id = params.get("v");
    if (id == null || !VIDEO_ID.matcher(id).matches()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    return accepted(Source.YOUTUBE, uri);
  }

  private static UrlDecision youtubeShort(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (segments.size() != 1 || !VIDEO_ID.matcher(segments.get(0)).matches()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    if (queryParams(uri.getQuery()).containsKey("list")) {
      return rejected(UrlDecision.Reason.PLAYLIST);
    }
    return accepted(Source.YOUTUBE, uri);
  }

  private static UrlDecision soundcloud(URI uri) {
    List<String> segments = pathSegments(uri.getPath());
    if (segments.isEmpty() || segments.size() > 3) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    if (segments.size() == 1) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    if (segments.size() == 2) {
      if (segments.get(1).equals("sets")) {
        return rejected(UrlDecision.Reason.PLAYLIST);
      }
      if (SOUNDCLOUD_SECTIONS.contains(segments.get(1))) {
        return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
      }
      return accepted(Source.SOUNDCLOUD, uri);
    }
    if (segments.get(1).equals("sets")) {
      return rejected(UrlDecision.Reason.PLAYLIST);
    }
    if (!UNLISTED_TOKEN.matcher(segments.get(2)).matches()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    return accepted(Source.SOUNDCLOUD, uri);
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
    if (segments.size() < 2 || segments.get(1).isEmpty()) {
      return rejected(UrlDecision.Reason.UNSUPPORTED_LINK);
    }
    return switch (segments.get(0)) {
      case "track", "episode" -> accepted(Source.SPOTIFY, uri);
      case "album", "playlist", "show" -> rejected(UrlDecision.Reason.PLAYLIST);
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
    // Short links hide the content type; the resolver classifies after resolving.
    return accepted(Source.SPOTIFY, uri);
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

  private static UrlDecision accepted(Source source, URI uri) {
    return new UrlDecision.Accepted(source, uri);
  }

  private static UrlDecision rejected(UrlDecision.Reason reason) {
    return new UrlDecision.Rejected(reason);
  }
}
