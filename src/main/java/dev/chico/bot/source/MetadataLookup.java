package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads track metadata from links that carry no audio. Apple Music uses the
 * public iTunes lookup; Spotify uses the Web API when credentials are set.
 */
public final class MetadataLookup {

  private final HttpClient http;
  private final URI itunesLookup;
  private final Duration timeout;
  private final SpotifyClient spotify;

  public MetadataLookup(URI itunesLookup, Duration timeout) {
    this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
        itunesLookup, timeout, null);
  }

  public MetadataLookup(URI itunesLookup, Duration timeout, SpotifyClient spotify) {
    this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
        itunesLookup, timeout, spotify);
  }

  MetadataLookup(HttpClient http, URI itunesLookup, Duration timeout) {
    this(http, itunesLookup, timeout, null);
  }

  MetadataLookup(
      HttpClient http, URI itunesLookup, Duration timeout, SpotifyClient spotify) {
    this.http = http;
    this.itunesLookup = itunesLookup;
    this.timeout = timeout;
    this.spotify = spotify;
  }

  public boolean spotifyEnabled() {
    return spotify != null;
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
