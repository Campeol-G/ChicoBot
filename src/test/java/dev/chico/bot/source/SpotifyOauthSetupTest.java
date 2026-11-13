package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpotifyOauthSetupTest {

  @Test
  void pkceChallengeMatchesRfc7636Vector() {
    assertEquals(
        "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
        SpotifyOauthSetup.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
  }

  @Test
  void tokenFileIsOwnerOnly(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("token");

    SpotifyOauthSetup.writeToken(file, "refresh-value");

    assertEquals("SPOTIFY_REFRESH_TOKEN=refresh-value\n", Files.readString(file));
    assertEquals(
        "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  @Test
  void authorizationUrlContainsPkceAndScopes() {
    String url =
        SpotifyOauthSetup.authorizationUrl(
            "client", "http://127.0.0.1:8888/callback", "challenge", "state");

    assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"));
    assertTrue(url.contains("code_challenge_method=S256"));
    assertTrue(url.contains("code_challenge=challenge"));
    assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8888%2Fcallback"));
    assertTrue(url.contains("playlist-read-private"));
    assertTrue(url.contains("state=state"));
  }
}
