package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppleMusicClientTest {

  private HttpServer server;
  private String baseUrl;
  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
  private final AtomicInteger limitedCalls = new AtomicInteger();
  private final AtomicReference<String> lastUserToken = new AtomicReference<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/catalog/br/playlists/pl.abc", exchange -> {
      requestedPaths.add(exchange.getRequestURI().getPath());
      respond(exchange, 200, playlistJson());
    });
    server.createContext("/v1/catalog/us/playlists/pl.paged", exchange -> {
      requestedPaths.add(exchange.getRequestURI().getPath());
      respond(
          exchange,
          200,
          """
          {"data":[{"id":"pl.paged","type":"playlists","relationships":{"tracks":{
            "data":[
              {"id":"1","type":"songs","attributes":{"name":"Song One","artistName":"Artist A"}},
              {"id":"2","type":"songs","attributes":{"name":"Song Two","artistName":"Artist B"}}
            ],
            "next":"/v1/catalog/us/playlists/pl.paged/tracks?offset=2"
          }}}]}
          """);
    });
    server.createContext("/v1/catalog/us/playlists/pl.paged/tracks", exchange -> {
      requestedPaths.add(exchange.getRequestURI().getPath());
      respond(
          exchange,
          200,
          """
          {"data":[
            {"id":"3","type":"songs","attributes":{"name":"Song Three","artistName":"Artist C"}}
          ],"next":null}
          """);
    });
    server.createContext("/v1/catalog/us/playlists/pl.missing", exchange -> respond(exchange, 404, "{}"));
    server.createContext("/v1/catalog/us/playlists/pl.denied", exchange -> respond(exchange, 401, "{}"));
    server.createContext("/v1/catalog/us/playlists/pl.limited", exchange -> {
      if (limitedCalls.getAndIncrement() == 0) {
        exchange.getResponseHeaders().set("Retry-After", "0");
        respond(exchange, 429, "{}");
      } else {
        requestedPaths.add(exchange.getRequestURI().getPath());
        respond(exchange, 200, playlistJson());
      }
    });
    server.createContext("/v1/catalog/us/playlists/pl.broken", exchange -> respond(exchange, 500, "{}"));
    server.createContext("/v1/me/library/playlists/p.lib", exchange -> {
      requestedPaths.add(exchange.getRequestURI().getPath());
      lastUserToken.set(exchange.getRequestHeaders().getFirst("Music-User-Token"));
      respond(exchange, 200, playlistJson());
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private AppleMusicClient client(String storefront) {
    return new AppleMusicClient(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        URI.create(baseUrl),
        jwt(System.currentTimeMillis() / 1000 + 3600),
        storefront,
        Optional.empty(),
        Duration.ofSeconds(5));
  }

  private AppleMusicClient userClient() {
    return new AppleMusicClient(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        URI.create(baseUrl),
        jwt(System.currentTimeMillis() / 1000 + 3600),
        "us",
        Optional.of("user-token"),
        Duration.ofSeconds(5));
  }

  private static String playlistJson() {
    return """
        {"data":[{"id":"pl.abc","type":"playlists","relationships":{"tracks":{
          "data":[
            {"id":"1","type":"songs","attributes":{"name":"Song One","artistName":"Artist A"}},
            {"id":"2","type":"songs","attributes":{"name":"Song Two","artistName":"Artist B"}}
          ],"next":null
        }}}]}
        """;
  }

  private static String jwt(long expiresAt) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"ES256\",\"kid\":\"key\"}"
        .getBytes(StandardCharsets.UTF_8));
    String payload =
        encoder.encodeToString(
            ("{\"iss\":\"team\",\"iat\":1,\"exp\":" + expiresAt + "}")
                .getBytes(StandardCharsets.UTF_8));
    return header + "." + payload + ".signature";
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Test
  void catalogPlaylistParsesTracksAndStorefront() throws Exception {
    AppleMusicClient.Content content =
        client("us")
            .resolvePlaylist(URI.create("https://music.apple.com/br/playlist/example/pl.abc"))
            .get();

    assertEquals(
        List.of(
            new AppleMusicClient.Track("Song One", "Artist A"),
            new AppleMusicClient.Track("Song Two", "Artist B")),
        content.tracks());
    assertEquals("/v1/catalog/br/playlists/pl.abc", requestedPaths.get(0));
  }

  @Test
  void paginationFollowsNext() throws Exception {
    AppleMusicClient.Content content =
        client("us")
            .resolvePlaylist(URI.create("https://music.apple.com/us/playlist/example/pl.paged"))
            .get();

    assertEquals(3, content.tracks().size());
    assertEquals(
        List.of(
            "/v1/catalog/us/playlists/pl.paged",
            "/v1/catalog/us/playlists/pl.paged/tracks"),
        requestedPaths);
  }

  @Test
  void notFoundIsUnavailable() throws Exception {
    AppleMusicClient.Content content =
        client("us")
            .resolvePlaylist(URI.create("https://music.apple.com/us/playlist/x/pl.missing"))
            .get();

    assertTrue(content.unavailable());
  }

  @Test
  void unauthorizedIsAnError() throws Exception {
    AppleMusicClient.Content content =
        client("us")
            .resolvePlaylist(URI.create("https://music.apple.com/us/playlist/x/pl.denied"))
            .get();

    assertTrue(content.error().contains("rejected"));
  }

  @Test
  void expiredDeveloperTokenFailsClearly() throws Exception {
    AppleMusicClient expired =
        new AppleMusicClient(
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
            URI.create(baseUrl),
            jwt(System.currentTimeMillis() / 1000 - 60),
            "us",
            Optional.empty(),
            Duration.ofSeconds(5));

    AppleMusicClient.Content content =
        expired.resolvePlaylist(URI.create("https://music.apple.com/us/playlist/x/pl.abc")).get();

    assertTrue(content.error().contains("expired"));
    assertTrue(requestedPaths.isEmpty());
  }

  @Test
  void rateLimitedRetriesOnce() throws Exception {
    AppleMusicClient.Content content =
        client("us")
            .resolvePlaylist(URI.create("https://music.apple.com/us/playlist/x/pl.limited"))
            .get();

    assertEquals(2, content.tracks().size());
    assertEquals(2, limitedCalls.get());
  }

  @Test
  void serverErrorIsTransient() throws Exception {
    AppleMusicClient.Content content =
        client("us")
            .resolvePlaylist(URI.create("https://music.apple.com/us/playlist/x/pl.broken"))
            .get();

    assertTrue(content.transientError());
  }

  @Test
  void libraryPlaylistRequiresUserToken() throws Exception {
    AppleMusicClient.Content content =
        client("us")
            .resolvePlaylist(URI.create("https://music.apple.com/library/playlist/p.lib"))
            .get();

    assertTrue(content.error().contains("APPLE_MUSIC_USER_TOKEN"));
    assertTrue(requestedPaths.isEmpty());
  }

  @Test
  void libraryPlaylistUsesTheUserTokenHeader() throws Exception {
    AppleMusicClient.Content content =
        userClient()
            .resolvePlaylist(URI.create("https://music.apple.com/library/playlist/p.lib"))
            .get();

    assertEquals(2, content.tracks().size());
    assertEquals("user-token", lastUserToken.get());
    assertEquals("/v1/me/library/playlists/p.lib", requestedPaths.get(0));
  }
}
