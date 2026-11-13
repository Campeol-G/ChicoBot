package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpotifyClientTest {

  private static final String TRACK_JSON =
      """
      {"name":"Never Gonna Give You Up","artists":[{"name":"Rick Astley"}]}
      """;

  private HttpServer server;
  private SpotifyClient client;
  private String baseUrl;
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger expiredCalls = new AtomicInteger();
  private final AtomicInteger limitedCalls = new AtomicInteger();
  private final AtomicInteger refreshRequests = new AtomicInteger();
  private final AtomicInteger clientCredentialRequests = new AtomicInteger();
  private final List<String> refreshTokensSeen = new CopyOnWriteArrayList<>();
  private final AtomicReference<String> lastMeAuth = new AtomicReference<>();
  private final AtomicReference<String> lastTrackAuth = new AtomicReference<>();
  private volatile boolean freeAccount;
  private volatile boolean failRefresh;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", exchange -> {
      tokenRequests.incrementAndGet();
      String body =
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      if (body.contains("grant_type=refresh_token")) {
        refreshRequests.incrementAndGet();
        refreshTokensSeen.add(String.valueOf(formValue(body, "refresh_token")));
        if (failRefresh) {
          respond(exchange, 400, "{}");
          return;
        }
        respond(
            exchange,
            200,
            "{\"access_token\":\"user-token\",\"refresh_token\":\"refresh-2\",\"expires_in\":3600}");
        return;
      }
      clientCredentialRequests.incrementAndGet();
      respond(exchange, 200, "{\"access_token\":\"client-token\",\"expires_in\":3600}");
    });
    server.createContext("/v1/me", exchange -> {
      lastMeAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      String product = freeAccount ? "free" : "premium";
      respond(
          exchange,
          200,
          "{\"product\":\"" + product + "\",\"display_name\":\"Chico\"}");
    });
    server.createContext("/v1/tracks/track1", exchange -> {
      lastTrackAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      respond(exchange, 200, TRACK_JSON);
    });
    server.createContext("/v1/tracks/expired", exchange -> {
      if (expiredCalls.getAndIncrement() == 0) {
        respond(exchange, 401, "{}");
      } else {
        respond(exchange, 200, TRACK_JSON);
      }
    });
    server.createContext("/v1/tracks/missing", exchange -> respond(exchange, 404, "{}"));
    server.createContext("/v1/tracks/broken", exchange -> respond(exchange, 500, "{}"));
    server.createContext("/v1/tracks/limited", exchange -> {
      if (limitedCalls.getAndIncrement() == 0) {
        exchange.getResponseHeaders().set("Retry-After", "0");
        respond(exchange, 429, "{}");
      } else {
        respond(exchange, 200, TRACK_JSON);
      }
    });
    server.createContext("/short", exchange -> {
      exchange.getResponseHeaders().set("Location", "https://open.spotify.com/track/track1");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/short-evil", exchange -> {
      exchange.getResponseHeaders().set("Location", "https://example.com/x");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/v1/playlists/private/tracks", exchange -> respond(exchange, 404, "{}"));
    server.createContext("/v1/playlists/list1/tracks", exchange -> {
      String query = exchange.getRequestURI().getQuery();
      boolean firstPage = query == null || query.contains("offset=0");
      if (firstPage) {
        respond(exchange, 200, page(50));
      } else {
        respond(exchange, 200, page(1));
      }
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    client =
        new SpotifyClient(
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(),
            Clock.systemUTC(),
            Duration.ofSeconds(5),
            "id",
            "secret",
            URI.create(baseUrl + "/token"),
            URI.create(baseUrl + "/v1"));
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private SpotifyClient userClient() {
    return new SpotifyClient(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        Clock.systemUTC(),
        Duration.ofSeconds(5),
        "id",
        "secret",
        Optional.of("refresh"),
        URI.create(baseUrl + "/token"),
        URI.create(baseUrl + "/v1"));
  }

  private static String formValue(String body, String key) {
    for (String pair : body.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0
          && pair.substring(0, eq).equals(key)
          && eq + 1 < pair.length()) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String page(int validTracks) {
    String item = "{\"track\":{\"name\":\"Song\",\"artists\":[{\"name\":\"Artist\"}]}}";
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < validTracks; i++) {
      if (i > 0) {
        items.append(',');
      }
      items.append(item);
    }
    if (validTracks > 0) {
      items.append(',');
    }
    items.append("{\"track\":null}");
    return "{\"items\":[" + items + "]}";
  }

  @Test
  void trackResolvesArtistAndTitle() throws Exception {
    SpotifyClient.Content content = client.track("track1").get();

    assertEquals(SpotifyClient.Kind.TRACK, content.kind());
    assertEquals(List.of("Rick Astley - Never Gonna Give You Up"), content.tracks());
    assertEquals(1, tokenRequests.get());
  }

  @Test
  void tokenCachedAcrossCalls() throws Exception {
    client.track("track1").get();
    client.track("track1").get();

    assertEquals(1, tokenRequests.get());
  }

  @Test
  void unauthorizedRefreshesTokenOnce() throws Exception {
    SpotifyClient.Content content = client.track("expired").get();

    assertEquals(SpotifyClient.Kind.TRACK, content.kind());
    assertEquals(2, tokenRequests.get());
  }

  @Test
  void missingTrackIsUnsupported() throws Exception {
    assertEquals(SpotifyClient.Kind.UNSUPPORTED, client.track("missing").get().kind());
  }

  @Test
  void playlistPaginatesAndSkipsNullEntries() throws Exception {
    SpotifyClient.Content content = client.playlist("list1").get();

    assertEquals(SpotifyClient.Kind.PLAYLIST, content.kind());
    assertEquals(51, content.tracks().size());
    assertTrue(content.tracks().stream().allMatch("Artist - Song"::equals));
  }

  @Test
  void privatePlaylistIsUnavailable() throws Exception {
    SpotifyClient.Content content = client.playlist("private").get();

    assertTrue(content.unavailable());
    assertEquals(SpotifyClient.Kind.PLAYLIST, content.kind());
  }

  @Test
  void rateLimitedRequestRetriesOnce() throws Exception {
    SpotifyClient.Content content = client.track("limited").get();

    assertEquals(SpotifyClient.Kind.TRACK, content.kind());
    assertEquals(2, limitedCalls.get());
  }

  @Test
  void serverErrorIsTransient() throws Exception {
    assertTrue(client.track("broken").get().transientError());
  }

  @Test
  void shortLinkFollowsOnlyOpenSpotify() throws Exception {
    SpotifyClient.Content track =
        client.followShortLink(URI.create(baseUrl + "/short")).get();
    assertEquals(SpotifyClient.Kind.TRACK, track.kind());

    SpotifyClient.Content evil =
        client.followShortLink(URI.create(baseUrl + "/short-evil")).get();
    assertEquals(SpotifyClient.Kind.UNSUPPORTED, evil.kind());
    assertTrue(!evil.transientError());
  }

  @Test
  void premiumUserTokenIsUsedAndReported() throws Exception {
    SpotifyClient user = userClient();

    SpotifyClient.SpotifyStatus status = user.status().get();

    assertTrue(status.configured());
    assertTrue(status.premium());
    assertEquals("Chico", status.displayName());
    assertTrue(user.userModeEnabled());
    assertEquals(1, refreshRequests.get());
    assertEquals("Bearer user-token", lastMeAuth.get());

    user.track("track1").get();

    assertEquals("Bearer user-token", lastTrackAuth.get());
    assertEquals(0, clientCredentialRequests.get());
  }

  @Test
  void freeAccountDisablesUserModeAndUsesClientCredentials() throws Exception {
    freeAccount = true;
    SpotifyClient user = userClient();

    SpotifyClient.SpotifyStatus status = user.status().get();

    assertFalse(status.premium());
    assertFalse(user.userModeEnabled());

    user.track("track1").get();

    assertEquals("Bearer client-token", lastTrackAuth.get());
    assertEquals(1, clientCredentialRequests.get());
  }

  @Test
  void failedRefreshFallsBackToClientCredentials() throws Exception {
    failRefresh = true;
    SpotifyClient user = userClient();

    SpotifyClient.SpotifyStatus status = user.status().get();

    assertTrue(status.configured());
    assertFalse(status.premium());
    assertFalse(status.error().isBlank());
    assertFalse(user.userModeEnabled());

    user.track("track1").get();

    assertEquals("Bearer client-token", lastTrackAuth.get());
    assertEquals(1, clientCredentialRequests.get());
  }

  @Test
  void rotatedRefreshTokenIsKeptInMemory() throws Exception {
    SpotifyClient user = userClient();

    user.status().get();
    user.status().get();

    assertEquals(List.of("refresh", "refresh-2"), refreshTokensSeen);
  }

  @Test
  void statusWithoutRefreshTokenIsNotConfigured() throws Exception {
    SpotifyClient.SpotifyStatus status = client.status().get();

    assertFalse(status.configured());
    assertFalse(status.premium());
  }
}
