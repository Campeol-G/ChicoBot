package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
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
    server.start();
    lookup =
        new MetadataLookup(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/lookup"),
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
}
