package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads track metadata from links that carry no audio. Apple Music uses the
 * public iTunes lookup; Spotify uses the public oEmbed endpoint (title only).
 */
public final class MetadataLookup {

  private static final URI SPOTIFY_OEMBED = URI.create("https://open.spotify.com/oembed");

  private final HttpClient http;
  private final HttpClient spotifyHttp;
  private final URI itunesLookup;
  private final URI spotifyOembed;
  private final Duration timeout;
  private final SpotifyClient spotify;

  public MetadataLookup(URI itunesLookup, Duration timeout) {
    this(itunesLookup, SPOTIFY_OEMBED, timeout);
  }

  public MetadataLookup(URI itunesLookup, URI spotifyOembed, Duration timeout) {
    this(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        itunesLookup,
        spotifyOembed,
        timeout,
        null);
  }

  public MetadataLookup(URI itunesLookup, Duration timeout, SpotifyClient spotify) {
    this(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        itunesLookup,
        SPOTIFY_OEMBED,
        timeout,
        spotify);
  }

  MetadataLookup(
      HttpClient http,
      HttpClient spotifyHttp,
      URI itunesLookup,
      URI spotifyOembed,
      Duration timeout) {
    this(http, spotifyHttp, itunesLookup, spotifyOembed, timeout, null);
  }

  MetadataLookup(
      HttpClient http,
      HttpClient spotifyHttp,
      URI itunesLookup,
      URI spotifyOembed,
      Duration timeout,
      SpotifyClient spotify) {
    this.http = http;
    this.spotifyHttp = spotifyHttp;
    this.itunesLookup = itunesLookup;
    this.spotifyOembed = spotifyOembed;
    this.timeout = timeout;
    this.spotify = spotify;
  }

  /** Result of a Spotify link lookup: a title, a non-track collection, or a failure. */
  public record SpotifyTitle(Kind kind, String title, boolean viaApi) {
    public enum Kind {
      FOUND,
      NOT_A_TRACK,
      FAILED
    }

    static SpotifyTitle found(String title, boolean viaApi) {
      return new SpotifyTitle(Kind.FOUND, title, viaApi);
    }

    static SpotifyTitle notATrack() {
      return new SpotifyTitle(Kind.NOT_A_TRACK, null, false);
    }

    static SpotifyTitle failed() {
      return new SpotifyTitle(Kind.FAILED, null, false);
    }
  }

  /**
   * Resolves a Spotify track link through the Web API when configured, then
   * falls back to the public oEmbed title.
   */
  public CompletableFuture<SpotifyTitle> spotifyTitle(URI uri) {
    if (spotify == null) {
      return oembedTitle(uri);
    }
    return spotify
        .resolve(uri)
        .thenCompose(
            content -> {
              if (content.kind() == SpotifyClient.Kind.TRACK
                  && !content.tracks().isEmpty()) {
                return CompletableFuture.completedFuture(
                    SpotifyTitle.found(content.tracks().get(0), true));
              }
              if (content.kind() == SpotifyClient.Kind.PLAYLIST) {
                return CompletableFuture.completedFuture(SpotifyTitle.notATrack());
              }
              return oembedTitle(uri);
            })
        .exceptionally(failure -> SpotifyTitle.failed());
  }

  private CompletableFuture<SpotifyTitle> oembedTitle(URI uri) {
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (host.equals("spotify.link")) {
      return followShortLink(uri);
    }
    if (!host.equals("open.spotify.com")) {
      return CompletableFuture.completedFuture(SpotifyTitle.failed());
    }
    return resolveTrack(uri);
  }

  CompletableFuture<SpotifyTitle> followShortLink(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
    return spotifyHttp
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenCompose(
            response -> {
              if (response.statusCode() < 300 || response.statusCode() >= 400) {
                return CompletableFuture.completedFuture(SpotifyTitle.failed());
              }
              String location = response.headers().firstValue("Location").orElse("");
              if (location.isBlank()) {
                return CompletableFuture.completedFuture(SpotifyTitle.failed());
              }
              URI target = uri.resolve(location);
              if (target.getScheme() == null
                  || !target.getScheme().equalsIgnoreCase("https")
                  || target.getHost() == null
                  || !target.getHost().equalsIgnoreCase("open.spotify.com")) {
                // POR QUE: only spotify.link -> open.spotify.com is allowed;
                // never fetch an arbitrary redirect target.
                return CompletableFuture.completedFuture(SpotifyTitle.failed());
              }
              return resolveTrack(target);
            })
        .exceptionally(failure -> SpotifyTitle.failed());
  }

  private CompletableFuture<SpotifyTitle> resolveTrack(URI uri) {
    String path = uri.getPath() == null ? "" : uri.getPath();
    List<String> segments =
        List.of(path.split("/")).stream().filter(s -> !s.isEmpty()).toList();
    if (segments.size() >= 2
        && segments.get(0).toLowerCase(Locale.ROOT).startsWith("intl-")) {
      segments = segments.subList(1, segments.size());
    }
    if (segments.size() < 2) {
      return CompletableFuture.completedFuture(SpotifyTitle.failed());
    }
    if (!segments.get(0).equals("track")) {
      return CompletableFuture.completedFuture(SpotifyTitle.notATrack());
    }
    String canonical = "https://open.spotify.com/track/" + segments.get(1);
    URI oembed =
        URI.create(
            spotifyOembed
                + "?url="
                + URLEncoder.encode(canonical, StandardCharsets.UTF_8));
    HttpRequest request = HttpRequest.newBuilder(oembed).timeout(timeout).GET().build();
    return spotifyHttp
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            response -> {
              if (response.statusCode() != 200) {
                return SpotifyTitle.failed();
              }
              try {
                String title = JsonBrowser.parse(response.body()).get("title").text();
                return title == null || title.isBlank()
                    ? SpotifyTitle.failed()
                    : SpotifyTitle.found(title.strip(), false);
              } catch (Exception e) {
                return SpotifyTitle.failed();
              }
            })
        .exceptionally(failure -> SpotifyTitle.failed());
  }

  public boolean spotifyEnabled() {
    return spotify != null;
  }

  /** Playlist metadata for the confirm preview (Spotify only). */
  public CompletableFuture<SpotifyClient.Content> spotifyPlaylist(UrlQuery query) {
    if (spotify == null || query.source() != Source.SPOTIFY) {
      return CompletableFuture.completedFuture(SpotifyClient.Content.unsupported());
    }
    return spotify.resolve(query.uri());
  }

  /** Resolves a metadata-only link to "artist - title" for text search. */
  public CompletableFuture<Optional<String>> artistTitle(UrlQuery query) {
    if (query.source() == Source.SPOTIFY) {
      if (spotify == null) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      return spotify
          .resolve(query.uri())
          .thenApply(
              content ->
                  content.kind() == SpotifyClient.Kind.TRACK && !content.tracks().isEmpty()
                      ? Optional.of(content.tracks().get(0))
                      : Optional.empty());
    }
    if (query.source() != Source.APPLE_MUSIC) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    String trackId = appleTrackId(query.uri());
    if (trackId == null) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    URI url = URI.create(itunesLookup + "?id=" + trackId + "&entity=song");
    HttpRequest request =
        HttpRequest.newBuilder(url).timeout(timeout).GET().build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        .thenApply(
            response -> {
              if (response.statusCode() != 200) {
                return Optional.<String>empty();
              }
              try (var body = response.body()) {
                return firstSong(JsonBrowser.parse(body));
              } catch (Exception e) {
                return Optional.<String>empty();
              }
            })
        .exceptionally(failure -> Optional.empty());
  }

  private static Optional<String> firstSong(JsonBrowser json) {
    if (json.get("resultCount").asLong(0) < 1) {
      return Optional.empty();
    }
    List<JsonBrowser> results = json.get("results").values();
    if (results.isEmpty()) {
      return Optional.empty();
    }
    // POR QUE: the lookup can return the album collection first (artistName
    // plus collectionName, no trackName), so scan for the song entry.
    JsonBrowser fallback = null;
    for (JsonBrowser result : results) {
      String artist = result.get("artistName").text();
      String title = result.get("trackName").text();
      if (artist == null || title == null || artist.isBlank() || title.isBlank()) {
        continue;
      }
      if (fallback == null) {
        fallback = result;
      }
      String wrapper = result.get("wrapperType").text();
      String kind = result.get("kind").text();
      if ("track".equals(wrapper) || "song".equals(kind)) {
        return Optional.of(artist.strip() + " - " + title.strip());
      }
    }
    if (fallback == null) {
      return Optional.empty();
    }
    String artist = fallback.get("artistName").text();
    String title = fallback.get("trackName").text();
    return Optional.of(artist.strip() + " - " + title.strip());
  }

  private static String appleTrackId(URI uri) {
    String id = queryParam(uri.getQuery(), "i");
    if (id != null && !id.isEmpty()) {
      return id;
    }
    List<String> segments =
        List.of(uri.getPath().split("/")).stream().filter(s -> !s.isEmpty()).toList();
    int song = segments.indexOf("song");
    if (song >= 0 && segments.size() > song + 1) {
      return segments.get(segments.size() - 1);
    }
    return null;
  }

  private static String queryParam(String query, String name) {
    if (query == null || query.isEmpty()) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return pair.substring(eq + 1);
      }
    }
    return null;
  }
}
