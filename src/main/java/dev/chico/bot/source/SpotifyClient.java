package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Spotify Web API reader. Metadata only: playback always resolves through
 * YouTube/SoundCloud search. The client uses client credentials by default and
 * an optional user refresh token for Premium/private data.
 */
public final class SpotifyClient {

  public enum Kind {
    TRACK,
    PLAYLIST,
    UNSUPPORTED
  }

  /** Resolved link: one search string, a playlist list, or unsupported. */
  public record Content(
      Kind kind, List<String> tracks, boolean unavailable, boolean transientError) {

    public Content(Kind kind, List<String> tracks, boolean unavailable) {
      this(kind, tracks, unavailable, false);
    }

    static Content track(String title) {
      return new Content(Kind.TRACK, List.of(title), false);
    }

    static Content playlist(List<String> tracks) {
      return new Content(Kind.PLAYLIST, List.copyOf(tracks), false);
    }

    static Content unavailablePlaylist() {
      return new Content(Kind.PLAYLIST, List.of(), true);
    }

    static Content unsupported() {
      return new Content(Kind.UNSUPPORTED, List.of(), false);
    }

    static Content transientFailure() {
      return new Content(Kind.UNSUPPORTED, List.of(), false, true);
    }
  }

  /** Health result for the optional user token. */
  public record SpotifyStatus(
      boolean configured, boolean premium, String displayName, String error) {
  }

  private static final int PAGE_SIZE = 50;
  private static final int MAX_PLAYLIST_TRACKS = 500;

  private final HttpClient http;
  private final Clock clock;
  private final Duration timeout;
  private final String clientId;
  private final String clientSecret;
  private final URI tokenUri;
  private final URI apiBase;

  private String accessToken;
  private Instant expiresAt = Instant.EPOCH;
  private boolean accessTokenIsUser;
  private volatile String currentRefreshToken;
  private volatile boolean userModeEnabled;
  private CompletableFuture<String> refreshInFlight;

  public SpotifyClient(String clientId, String clientSecret, Duration timeout) {
    this(clientId, clientSecret, Optional.empty(), timeout);
  }

  public SpotifyClient(
      String clientId, String clientSecret, Optional<String> refreshToken, Duration timeout) {
    this(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        Clock.systemUTC(),
        timeout,
        clientId,
        clientSecret,
        refreshToken,
        URI.create("https://accounts.spotify.com/api/token"),
        URI.create("https://api.spotify.com/v1"));
  }

  SpotifyClient(
      HttpClient http,
      Clock clock,
      Duration timeout,
      String clientId,
      String clientSecret,
      URI tokenUri,
      URI apiBase) {
    this(
        http,
        clock,
        timeout,
        clientId,
        clientSecret,
        Optional.empty(),
        tokenUri,
        apiBase);
  }

  SpotifyClient(
      HttpClient http,
      Clock clock,
      Duration timeout,
      String clientId,
      String clientSecret,
      Optional<String> refreshToken,
      URI tokenUri,
      URI apiBase) {
    this.http = http;
    this.clock = clock;
    this.timeout = timeout;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.currentRefreshToken = refreshToken.orElse(null);
    this.userModeEnabled = refreshToken.isPresent();
    this.tokenUri = tokenUri;
    this.apiBase = apiBase;
  }

  public boolean userModeEnabled() {
    return userModeEnabled;
  }

  /** Refreshes the user token, checks /v1/me and records Premium status. */
  public CompletableFuture<SpotifyStatus> status() {
    if (currentRefreshToken == null) {
      return CompletableFuture.completedFuture(new SpotifyStatus(false, false, null, null));
    }
    return refreshUserToken(currentRefreshToken)
        .thenCompose(token -> getJsonWithToken("/me", token, false))
        .thenApply(
            result -> {
              if (result.status() != 200) {
                userModeEnabled = false;
                invalidateToken();
                return new SpotifyStatus(
                    true, false, null, "Spotify profile status " + result.status());
              }
              String product = result.body().get("product").text();
              String displayName = result.body().get("display_name").text();
              boolean premium = "premium".equalsIgnoreCase(product);
              userModeEnabled = premium;
              if (!premium) {
                invalidateToken();
              }
              return new SpotifyStatus(true, premium, displayName, null);
            })
        .exceptionally(
            failure -> {
              userModeEnabled = false;
              invalidateToken();
              return new SpotifyStatus(true, false, null, "Spotify user token refresh failed");
            });
  }

  /** Resolves track/playlist links and spotify.link shorts to metadata. */
  public CompletableFuture<Content> resolve(URI uri) {
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
    if (host.equals("spotify.link")) {
      return followShortLink(uri);
    }
    Optional<Ref> ref = parseOpenSpotify(uri);
    if (ref.isEmpty()) {
      return CompletableFuture.completedFuture(Content.unsupported());
    }
    return switch (ref.orElseThrow().kind()) {
      case "track" -> track(ref.orElseThrow().id());
      case "playlist" -> playlist(ref.orElseThrow().id());
      default -> CompletableFuture.completedFuture(Content.unsupported());
    };
  }

  public CompletableFuture<Content> track(String id) {
    return getJson("/tracks/" + id, true)
        .thenApply(
            json -> {
              if (json.status() == 200) {
                String title = artistTitle(json.body());
                return title == null ? Content.transientFailure() : Content.track(title);
              }
              if (json.status() == 403 || json.status() == 404) {
                return Content.unsupported();
              }
              return Content.transientFailure();
            })
        .exceptionally(failure -> Content.transientFailure());
  }

  public CompletableFuture<Content> playlist(String id) {
    List<String> tracks = new ArrayList<>();
    return playlistPage(id, 0, tracks)
        .thenApply(ignored -> Content.playlist(tracks))
        .exceptionally(
            failure -> {
              Throwable cause =
                  failure instanceof CompletionException ? failure.getCause() : failure;
              return cause instanceof UnavailablePlaylistException
                  ? Content.unavailablePlaylist()
                  : Content.transientFailure();
            });
  }

  private CompletableFuture<Void> playlistPage(String id, int offset, List<String> tracks) {
    String path =
        "/playlists/" + id + "/tracks?limit=" + PAGE_SIZE + "&offset=" + offset;
    return getJson(path, true)
        .thenCompose(
            json -> {
              if (json.status() == 403 || json.status() == 404) {
                throw new CompletionException(new UnavailablePlaylistException());
              }
              if (json.status() != 200) {
                throw new CompletionException(
                    new IllegalStateException("Spotify playlist status " + json.status()));
              }
              List<JsonBrowser> items = json.body().get("items").values();
              for (JsonBrowser item : items) {
                JsonBrowser track = item.get("track");
                String title = artistTitle(track);
                if (title != null) {
                  tracks.add(title);
                }
              }
              if (items.isEmpty() || tracks.size() >= MAX_PLAYLIST_TRACKS) {
                return CompletableFuture.completedFuture(null);
              }
              long total = json.body().get("total").asLong(-1);
              boolean more =
                  total >= 0
                      ? (long) offset + items.size() < total
                      : items.size() >= PAGE_SIZE;
              if (!more) {
                return CompletableFuture.completedFuture(null);
              }
              return playlistPage(id, offset + items.size(), tracks);
            });
  }

  private static String artistTitle(JsonBrowser track) {
    if (track == null || track.isNull()) {
      return null;
    }
    String name = track.get("name").text();
    String artist = track.get("artists").index(0).get("name").text();
    if (name == null || name.isBlank() || artist == null || artist.isBlank()) {
      return null;
    }
    return artist.strip() + " - " + name.strip();
  }

  CompletableFuture<Content> followShortLink(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenCompose(
            response -> {
              if (response.statusCode() < 300 || response.statusCode() >= 400) {
                return CompletableFuture.completedFuture(Content.unsupported());
              }
              String location = response.headers().firstValue("Location").orElse("");
              if (location.isBlank()) {
                return CompletableFuture.completedFuture(Content.unsupported());
              }
              URI target = uri.resolve(location);
              if (target.getScheme() == null
                  || !target.getScheme().equalsIgnoreCase("https")
                  || target.getHost() == null
                  || !target.getHost().equalsIgnoreCase("open.spotify.com")) {
                // POR QUE: only spotify.link -> open.spotify.com is allowed;
                // never fetch an arbitrary redirect target.
                return CompletableFuture.completedFuture(Content.unsupported());
              }
              Optional<Ref> ref = parseOpenSpotify(target);
              if (ref.isEmpty()) {
                return CompletableFuture.completedFuture(Content.unsupported());
              }
              return switch (ref.orElseThrow().kind()) {
                case "track" -> track(ref.orElseThrow().id());
                case "playlist" -> playlist(ref.orElseThrow().id());
                default -> CompletableFuture.completedFuture(Content.unsupported());
              };
            })
        .exceptionally(failure -> Content.transientFailure());
  }

  private record Ref(String kind, String id) {
  }

  private static Optional<Ref> parseOpenSpotify(URI uri) {
    if (uri.getHost() == null
        || !uri.getHost().equalsIgnoreCase("open.spotify.com")) {
      return Optional.empty();
    }
    List<String> segments =
        List.of(uri.getPath().split("/")).stream().filter(s -> !s.isEmpty()).toList();
    if (segments.size() >= 2 && segments.get(0).toLowerCase().startsWith("intl-")) {
      segments = segments.subList(1, segments.size());
    }
    if (segments.size() < 2) {
      return Optional.empty();
    }
    return Optional.of(new Ref(segments.get(0), segments.get(1)));
  }

  private record JsonResult(int status, JsonBrowser body) {
  }

  private CompletableFuture<JsonResult> getJson(String path, boolean retry) {
    return token().thenCompose(bearer -> getJsonWithToken(path, bearer, retry));
  }

  private CompletableFuture<JsonResult> getJsonWithToken(
      String path, String bearer, boolean retry) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(apiBase + path))
            .timeout(timeout)
            .header("Authorization", "Bearer " + bearer)
            .GET()
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenCompose(response -> handle(path, retry, bearer, response));
  }

  private CompletableFuture<JsonResult> handle(
      String path, boolean retry, String bearer, HttpResponse<String> response) {
    int status = response.statusCode();
    if (status == 401 && retry) {
      invalidateToken();
      return getJson(path, false);
    }
    if (status == 429 && retry) {
      long waitS = retryAfterSeconds(response);
      return CompletableFuture.supplyAsync(
              () -> null, CompletableFuture.delayedExecutor(waitS, TimeUnit.SECONDS))
          .thenCompose(ignored -> getJson(path, false));
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
            // shortcut: cap the wait at 30s, longer Spotify waits are rare,
            // upgrade to async scheduling if that changes.
            return Math.min(30, Math.max(0, Long.parseLong(value.strip())));
          } catch (NumberFormatException ignored) {
            return 1L;
          }
        })
        .orElse(1L);
  }

  private CompletableFuture<String> token() {
    CompletableFuture<String> refresh;
    synchronized (this) {
      if (accessToken != null
          && clock.instant().isBefore(expiresAt.minusSeconds(60))
          && (!userModeEnabled || accessTokenIsUser)) {
        return CompletableFuture.completedFuture(accessToken);
      }
      if (refreshInFlight != null) {
        return refreshInFlight;
      }
      if (userModeEnabled && currentRefreshToken != null) {
        refresh =
            refreshUserToken(currentRefreshToken)
                .exceptionallyCompose(
                    failure -> {
                      userModeEnabled = false;
                      invalidateToken();
                      return requestClientCredentials();
                    });
      } else {
        refresh = requestClientCredentials();
      }
      refresh =
          refresh.whenComplete(
              (token, failure) -> {
                synchronized (this) {
                  refreshInFlight = null;
                }
              });
      refreshInFlight = refresh;
    }
    return refresh;
  }

  private CompletableFuture<String> refreshUserToken(String refreshValue) {
    String body =
        "grant_type=refresh_token"
            + "&client_id="
            + encode(clientId)
            + "&refresh_token="
            + encode(refreshValue);
    HttpRequest request =
        HttpRequest.newBuilder(tokenUri)
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            response -> {
              if (response.statusCode() != 200) {
                throw new CompletionException(
                    new IllegalStateException("Spotify refresh status " + response.statusCode()));
              }
              try {
                JsonBrowser json = JsonBrowser.parse(response.body());
                String token = json.get("access_token").text();
                String rotated = json.get("refresh_token").text();
                long expiresIn = json.get("expires_in").asLong(3600);
                if (token == null || token.isBlank()) {
                  throw new IllegalStateException("Spotify refresh token missing");
                }
                synchronized (this) {
                  currentRefreshToken =
                      rotated == null || rotated.isBlank() ? refreshValue : rotated;
                  accessToken = token;
                  expiresAt = clock.instant().plusSeconds(expiresIn);
                  accessTokenIsUser = true;
                }
                return token;
              } catch (IllegalStateException e) {
                throw new CompletionException(e);
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });
  }

  private CompletableFuture<String> requestClientCredentials() {
    String basic =
        Base64.getEncoder()
            .encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    HttpRequest request =
        HttpRequest.newBuilder(tokenUri)
            .timeout(timeout)
            .header("Authorization", "Basic " + basic)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build();
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenApply(
            response -> {
              if (response.statusCode() != 200) {
                throw new CompletionException(
                    new IllegalStateException("Spotify token status " + response.statusCode()));
              }
              try {
                JsonBrowser json = JsonBrowser.parse(response.body());
                String token = json.get("access_token").text();
                long expiresIn = json.get("expires_in").asLong(3600);
                if (token == null || token.isBlank()) {
                  throw new IllegalStateException("Spotify token missing");
                }
                synchronized (this) {
                  accessToken = token;
                  expiresAt = clock.instant().plusSeconds(expiresIn);
                  accessTokenIsUser = false;
                }
                return token;
              } catch (IllegalStateException e) {
                throw new CompletionException(e);
              } catch (Exception e) {
                throw new CompletionException(e);
              }
            });
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private void invalidateToken() {
    synchronized (this) {
      accessToken = null;
      expiresAt = Instant.EPOCH;
    }
  }

  /** Internal marker for a private/unavailable playlist. */
  static final class UnavailablePlaylistException extends RuntimeException {
  }
}
