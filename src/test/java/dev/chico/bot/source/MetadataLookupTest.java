package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetadataLookupTest {

  private static final String ITUNES_JSON =
      """
      {"resultCount":1,"results":[
      {"wrapperType":"track","kind":"song","trackId":1559523359,
      "artistName":"Rick Astley","trackName":"Never Gonna Give You Up"}]}
      """;

  private static final String ITUNES_COLLECTION_FIRST_JSON =
      """
      {"resultCount":2,"results":[
      {"wrapperType":"collection","collectionId":1559523357,
      "artistName":"Rick Astley","collectionName":"Whenever You Need Somebody"},
      {"wrapperType":"track","kind":"song","trackId":1559523359,
      "artistName":"Rick Astley","trackName":"Never Gonna Give You Up"}]}
      """;

  private HttpServer server;
  private MetadataLookup lookup;
  private MetadataLookup spotifyLookup;
  private String baseUrl;
  private final AtomicReference<String> lastOembedQuery = new AtomicReference<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/lookup",
        exchange -> {
          String query = exchange.getRequestURI().getQuery();
          byte[] body =
              query != null && query.contains("id=1559523359")
                  ? ITUNES_JSON.getBytes(StandardCharsets.UTF_8)
                  : query != null && query.contains("id=777")
                      ? ITUNES_COLLECTION_FIRST_JSON.getBytes(StandardCharsets.UTF_8)
                      : "{\"resultCount\":0,\"results\":[]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext("/oembed", exchange -> {
      String query = exchange.getRequestURI().getQuery();
      lastOembedQuery.set(query);
      String body;
      int status;
      if (query != null && query.contains("broken")) {
        status = 500;
        body = "{}";
      } else if (query != null && query.contains("missing")) {
        status = 200;
        body = "{}";
      } else if (query != null && query.contains("blank")) {
        status = 200;
        body = "{\"title\":\"   \"}";
      } else {
        status = 200;
        body = "{\"title\":\"Never Gonna Give You Up\"}";
      }
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.createContext("/short", exchange -> {
      exchange
          .getResponseHeaders()
          .set("Location", "https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/short-playlist", exchange -> {
      exchange
          .getResponseHeaders()
          .set("Location", "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.createContext("/short-evil", exchange -> {
      exchange.getResponseHeaders().set("Location", "https://example.com/x");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
    });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    lookup =
        new MetadataLookup(
            URI.create(baseUrl + "/lookup"), Duration.ofSeconds(5));
    spotifyLookup =
        new MetadataLookup(
            URI.create(baseUrl + "/lookup"),
            URI.create(baseUrl + "/oembed"),
            Duration.ofSeconds(5));
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void appleAlbumLinkResolvesToArtistAndTitle() throws Exception {
    UrlQuery query =
        new UrlQuery(
            Source.APPLE_MUSIC,
            URI.create("https://music.apple.com/br/album/never-gonna-give-you-up/1559523357?i=1559523359"));

    Optional<String> result = lookup.artistTitle(query).get();

    assertEquals(Optional.of("Rick Astley - Never Gonna Give You Up"), result);
  }

  @Test
  void appleSongLinkResolvesToArtistAndTitle() throws Exception {
    UrlQuery query =
        new UrlQuery(
            Source.APPLE_MUSIC,
            URI.create("https://music.apple.com/us/song/never-gonna-give-you-up/1559523359"));

    Optional<String> result = lookup.artistTitle(query).get();

    assertEquals(Optional.of("Rick Astley - Never Gonna Give You Up"), result);
  }

  @Test
  void collectionFirstStillResolvesSong() throws Exception {
    UrlQuery query =
        new UrlQuery(
            Source.APPLE_MUSIC,
            URI.create("https://music.apple.com/br/album/whatever/1559523357?i=777"));

    Optional<String> result = lookup.artistTitle(query).get();

    assertEquals(Optional.of("Rick Astley - Never Gonna Give You Up"), result);
  }

  @Test
  void unknownAppleIdGivesEmpty() throws Exception {
    UrlQuery query =
        new UrlQuery(
            Source.APPLE_MUSIC,
            URI.create("https://music.apple.com/br/album/whatever/1?i=2"));

    assertTrue(lookup.artistTitle(query).get().isEmpty());
  }

  @Test
  void nonMetadataSourceGivesEmptyWithoutRequest() throws Exception {
    UrlQuery query =
        new UrlQuery(
            Source.YOUTUBE, URI.create("https://youtube.com/watch?v=dQw4w9WgXcQ"));

    assertTrue(lookup.artistTitle(query).get().isEmpty());
  }

  @Test
  void unreachableServerGivesEmpty() throws Exception {
    MetadataLookup broken =
        new MetadataLookup(URI.create("http://127.0.0.1:1/lookup"), Duration.ofMillis(300));
    UrlQuery query =
        new UrlQuery(
            Source.APPLE_MUSIC,
            URI.create("https://music.apple.com/br/album/whatever/1559523357?i=1559523359"));

    assertTrue(broken.artistTitle(query).get().isEmpty());
  }

  @Test
  void spotifyTrackLinkResolvesOembedTitle() throws Exception {
    MetadataLookup.SpotifyTitle result =
        spotifyLookup
            .spotifyTitle(URI.create("https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"))
            .get();

    assertEquals(MetadataLookup.SpotifyTitle.Kind.FOUND, result.kind());
    assertEquals("Never Gonna Give You Up", result.title());
    assertTrue(lastOembedQuery.get()
        .contains("url=https://open.spotify.com/track/4PTG3Z6ehGkBFwjybzWkR8"));
  }

  @Test
  void spotifyPlaylistLinkIsNotATrack() throws Exception {
    MetadataLookup.SpotifyTitle result =
        spotifyLookup
            .spotifyTitle(URI.create("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
            .get();

    assertEquals(MetadataLookup.SpotifyTitle.Kind.NOT_A_TRACK, result.kind());
  }

  @Test
  void spotifyShortLinkToTrackResolves() throws Exception {
    MetadataLookup.SpotifyTitle result =
        spotifyLookup.followShortLink(URI.create(baseUrl + "/short")).get();

    assertEquals(MetadataLookup.SpotifyTitle.Kind.FOUND, result.kind());
    assertEquals("Never Gonna Give You Up", result.title());
  }

  @Test
  void spotifyShortLinkToPlaylistIsNotATrack() throws Exception {
    MetadataLookup.SpotifyTitle result =
        spotifyLookup.followShortLink(URI.create(baseUrl + "/short-playlist")).get();

    assertEquals(MetadataLookup.SpotifyTitle.Kind.NOT_A_TRACK, result.kind());
  }

  @Test
  void spotifyShortLinkToOtherHostIsFailed() throws Exception {
    MetadataLookup.SpotifyTitle result =
        spotifyLookup.followShortLink(URI.create(baseUrl + "/short-evil")).get();

    assertEquals(MetadataLookup.SpotifyTitle.Kind.FAILED, result.kind());
  }

  @Test
  void spotifyOembedBadPayloadIsFailed() throws Exception {
    for (String id : List.of("broken", "missing", "blank")) {
      MetadataLookup.SpotifyTitle result =
          spotifyLookup
              .spotifyTitle(URI.create("https://open.spotify.com/track/" + id))
              .get();
      assertEquals(MetadataLookup.SpotifyTitle.Kind.FAILED, result.kind(), id);
    }
  }

  @Test
  void spotifyNonSpotifyHostIsFailed() throws Exception {
    MetadataLookup.SpotifyTitle result =
        spotifyLookup.spotifyTitle(URI.create("https://example.com/track/x")).get();

    assertEquals(MetadataLookup.SpotifyTitle.Kind.FAILED, result.kind());
  }
}
