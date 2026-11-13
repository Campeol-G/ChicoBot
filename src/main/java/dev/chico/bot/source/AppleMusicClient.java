package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Apple Music API reader for playlists. Catalog playlists need a developer
 * token; library playlists additionally need a Music User Token. Metadata only:
 * playback always resolves through YouTube/SoundCloud search.
 */
public final class AppleMusicClient {

  public record Track(String title, String artist) {
  }

  public record Content(
      List<Track> tracks, boolean unavailable, boolean transientError, String error) {

    public Content(List<Track> tracks, boolean unavailable, boolean transientError) {
      this(tracks, unavailable, transientError, null);
    }

    static Content unavailablePlaylist() {
      return new Content(List.of(), true, false);
    }

    static Content transientFailure() {
      return new Content(List.of(), false, true);
    }

    static Content error(String message) {
      return new Content(List.of(), false, false, message);
    }
  }

  private static final int PAGE_SIZE = 100;
  private static final int MAX_TRACKS = 500;

  private final HttpClient http;
  private final URI apiBase;
  private final String developerToken;
  private final String storefront;
  private final Optional<String> userToken;
  private final Duration timeout;

  public AppleMusicClient(
      String developerToken,
      String storefront,
      Optional<String> userToken,
      Duration timeout) {
    this(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        URI.create("https://api.music.apple.com"),
        developerToken,
        storefront,
        userToken,
        timeout);
  }

  /** Custom endpoint factory for proxies and local-server tests. */
  public static AppleMusicClient forEndpoint(
      String developerToken,
      String storefront,
      Optional<String> userToken,
      Duration timeout,
      URI apiBase) {
    return new AppleMusicClient(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        apiBase,
        developerToken,
        storefront,
        userToken,
        timeout);
  }

  AppleMusicClient(
      HttpClient http,
      URI apiBase,
      String developerToken,
      String storefront,
      Optional<String> userToken,
      Duration timeout) {
    this.http = http;
    this.apiBase = apiBase;
    this.developerToken = developerToken;
    this.storefront = storefront;
    this.userToken = userToken;
    this.timeout = timeout;
  }

  public CompletableFuture<Content> resolvePlaylist(URI uri) {
    Optional<Ref> parsed = parse(uri);
    if (parsed.isEmpty()) {
      return CompletableFuture.completedFuture(
          Content.error("That Apple Music link is not a supported playlist."));
    }
    if (developerToken == null || developerToken.isBlank()) {
      return CompletableFuture.completedFuture(
          Content.error("Apple Music playlists need APPLE_MUSIC_DEVELOPER_TOKEN."));
    }
    if (tokenExpired(developerToken)) {
      return CompletableFuture.completedFuture(
          Content.error("Apple Music developer token expired; generate a new one."));
    }
    Ref ref = parsed.orElseThrow();
    if (ref.library()) {
      if (userToken.isEmpty()) {
        return CompletableFuture.completedFuture(
            Content.error("Apple Music library playlists need APPLE_MUSIC_USER_TOKEN."));
      }
      String path =
          "/v1/me/library/playlists/"
              + ref.id()
              + "?include=tracks&limit="
              + PAGE_SIZE;
      return fetch(path, new ArrayList<>());
    }
    String path =
        "/v1/catalog/"
            + ref.storefront()
            + "/playlists/"
            + ref.id()
            + "?include=tracks&limit="
            + PAGE_SIZE;
    return fetch(path, new ArrayList<>());
  }

  private CompletableFuture<Content> fetch(String path, List<Track> tracks) {
    return get(path, true)
        .thenCompose(
            result -> {
              if (result.status() == 404) {
                return CompletableFuture.completedFuture(Content.unavailablePlaylist());
              }
              if (result.status() == 401 || result.status() == 403) {
                return CompletableFuture.completedFuture(
                    Content.error(
                        "Apple Music credentials were rejected. Check the developer and Music"
                            + " User tokens."));
              }
              if (result.status() != 200) {
                return CompletableFuture.completedFuture(Content.transientFailure());
              }
              JsonBrowser body = result.body();
              JsonBrowser tracksNode = body;
              if (!body.get("data").values().isEmpty()) {
                JsonBrowser first = body.get("data").index(0);
                JsonBrowser relationship = first.get("relationships").get("tracks");
                if (!relationship.isNull()) {
                  tracksNode = relationship;
                }
              }
              for (JsonBrowser item : tracksNode.get("data").values()) {
                JsonBrowser attributes = item.get("attributes");
                String title = attributes.get("name").text();
                String artist = attributes.get("artistName").text();
                if (title != null
                    && !title.isBlank()
                    && artist != null
                    && !artist.isBlank()
                    && tracks.size() < MAX_TRACKS) {
                  tracks.add(new Track(title.strip(), artist.strip()));
                }
              }
              String next = tracksNode.get("next").text();
              if (next == null
                  || next.isBlank()
                  || tracks.size() >= MAX_TRACKS) {
                return CompletableFuture.completedFuture(new Content(List.copyOf(tracks), false, false));
              }
              URI nextUri = apiBase.resolve(next);
              if (nextUri.getHost() == null
                  || !nextUri.getHost().equalsIgnoreCase(apiBase.getHost())) {
                // POR QUE: never follow a next link to another host.
                return CompletableFuture.completedFuture(Content.transientFailure());
              }
              return fetch(nextUri.getPath()
                  + (nextUri.getQuery() == null ? "" : "?" + nextUri.getQuery()), tracks);
            })
        .exceptionally(failure -> Content.transientFailure());
  }

  private record JsonResult(int status, JsonBrowser body) {
  }

  private CompletableFuture<JsonResult> get(String path, boolean retry) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(apiBase + path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + developerToken)
            .GET();
    userToken.ifPresent(token -> builder.header("Music-User-Token", token));
    return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
        .thenCompose(response -> handle(path, retry, response));
  }

  private CompletableFuture<JsonResult> handle(
      String path, boolean retry, HttpResponse<String> response) {
    int status = response.statusCode();
    if (status == 429 && retry) {
      long waitS = retryAfterSeconds(response);
      return CompletableFuture.supplyAsync(
              () -> null, CompletableFuture.delayedExecutor(waitS, TimeUnit.SECONDS))
          .thenCompose(ignored -> get(path, false));
    }
    if (status != 200) {
      return CompletableFuture.completedFuture(new JsonResult(status, JsonBrowser.NULL_BROWSER));
    }
    try {
      return CompletableFuture.completedFuture(
          new JsonResult(status, JsonBrowser.parse(response.body())));
    } catch (Exception e) {
      return CompletableFuture.completedFuture(new JsonResult(500, JsonBrowser.NULL_BROWSER));
    }
  }

  private static long retryAfterSeconds(HttpResponse<?> response) {
    return response.headers().firstValue("Retry-After")
        .map(value -> {
          try {
            // shortcut: cap the wait at 30s, longer Apple waits are rare,
            // upgrade to async scheduling if that changes.
            return Math.min(30, Math.max(0, Long.parseLong(value.strip())));
          } catch (NumberFormatException ignored) {
            return 1L;
          }
        })
        .orElse(1L);
  }

  private static boolean tokenExpired(String token) {
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      return true;
    }
    try {
      String payload =
          new String(
              Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      long exp = JsonBrowser.parse(payload).get("exp").asLong(0);
      return exp <= System.currentTimeMillis() / 1000;
    } catch (Exception e) {
      return true;
    }
  }

  private Optional<Ref> parse(URI uri) {
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if (!host.equals("music.apple.com") && !host.equals("www.music.apple.com")) {
      return Optional.empty();
    }
    List<String> segments =
        List.of(uri.getPath().split("/")).stream().filter(s -> !s.isEmpty()).toList();
    int playlist = segments.indexOf("playlist");
    if (playlist < 0 || playlist + 1 >= segments.size()) {
      return Optional.empty();
    }
    String id = segments.get(segments.size() - 1);
    if (!id.matches("[A-Za-z0-9._-]{1,128}")) {
      return Optional.empty();
    }
    boolean library = playlist > 0 && segments.get(playlist - 1).equals("library");
    if (library) {
      return Optional.of(new Ref(true, "", id));
    }
    String selected = storefront;
    if (playlist > 0 && segments.get(playlist - 1).matches("[a-z]{2}")) {
      selected = segments.get(playlist - 1);
    }
    return Optional.of(new Ref(false, selected, id));
  }

  private record Ref(boolean library, String storefront, String id) {
  }
}
