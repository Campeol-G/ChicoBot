package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spotify Authorization Code + PKCE helper. Writes the refresh token to a
 * mode-600 file when a path argument is given; otherwise prints it for manual
 * use. The chicoBot setup wizard calls the file form.
 */
public final class SpotifyOauthSetup {

  private static final String AUTHORIZE = "https://accounts.spotify.com/authorize";
  private static final String TOKEN = "https://accounts.spotify.com/api/token";
  private static final String REDIRECT_URI = "http://127.0.0.1:8888/callback";
  private static final String SCOPES =
      "playlist-read-private playlist-read-collaborative user-read-private";

  private SpotifyOauthSetup() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 1) {
      System.err.println("usage: SpotifyOauthSetup [refresh-token-file]");
      System.exit(2);
    }
    String clientId = System.getenv("SPOTIFY_CLIENT_ID");
    if (clientId == null || clientId.isBlank()) {
      System.err.println("Set SPOTIFY_CLIENT_ID to the Spotify app client id.");
      System.exit(1);
    }

    String verifier = randomToken(64);
    String challenge = codeChallenge(verifier);
    String state = randomToken(16);
    System.out.println("Open this URL in a browser and authorize the app:");
    System.out.println(authorizationUrl(clientId, REDIRECT_URI, challenge, state));

    AtomicReference<String> code = new AtomicReference<>();
    AtomicReference<String> error = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8888), 0);
    server.createContext(
        "/callback",
        exchange -> {
          String query = exchange.getRequestURI().getQuery();
          String callbackError = callbackError(query, state);
          if (callbackError != null) {
            error.set(callbackError);
            respond(exchange, 400, "Authorization failed: " + callbackError + ".");
          } else {
            code.set(queryParam(query, "code"));
            respond(exchange, 200, "Authorization received. You can close this tab.");
          }
          done.countDown();
        });
    server.start();
    try {
      if (!done.await(5, TimeUnit.MINUTES)) {
        System.err.println("OAUTH TIMEOUT: no authorization within 5 minutes.");
        System.exit(1);
      }
    } finally {
      server.stop(0);
    }
    if (error.get() != null) {
      System.err.println("OAUTH FAILED: " + error.get());
      System.exit(1);
    }

    String refresh = exchangeCode(clientId, verifier, code.get());
    if (args.length == 1) {
      writeToken(Path.of(args[0]), refresh);
      System.out.println("Refresh token saved to " + args[0] + " (mode 600).");
    } else {
      System.out.println("SPOTIFY_REFRESH_TOKEN=" + refresh);
      System.out.println("Store it in ~/.config/chicos-bot/env (mode 600). Never share it.");
    }
  }

  static String codeChallenge(String verifier) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  static String authorizationUrl(
      String clientId, String redirectUri, String challenge, String state) {
    return AUTHORIZE
        + "?client_id="
        + encode(clientId)
        + "&response_type=code"
        + "&redirect_uri="
        + encode(redirectUri)
        + "&scope="
        + encode(SCOPES)
        + "&code_challenge_method=S256"
        + "&code_challenge="
        + encode(challenge)
        + "&state="
        + encode(state);
  }

  static void writeToken(Path file, String token) throws IOException {
    if (Files.isSymbolicLink(file)) {
      throw new IOException("Refuses to write token through a symbolic link: " + file);
    }
    if (Files.exists(file)) {
      try {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Linux is the only supported platform for this bot.
      }
    }
    try {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (FileAlreadyExistsException ignored) {
      // Keep the existing file, then truncate it below.
    }
    Files.writeString(
        file,
        "SPOTIFY_REFRESH_TOKEN=" + token + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // Linux is the only supported platform for this bot.
    }
  }

  static String tokenBody(String clientId, String verifier, String code) {
    return "grant_type=authorization_code"
        + "&code="
        + encode(code)
        + "&redirect_uri="
        + encode(REDIRECT_URI)
        + "&client_id="
        + encode(clientId)
        + "&code_verifier="
        + encode(verifier);
  }

  private static String exchangeCode(String clientId, String verifier, String code)
      throws Exception {
    String body = tokenBody(clientId, verifier, code);
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(TOKEN))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Spotify token exchange failed: " + response.statusCode());
    }
    String refresh = JsonBrowser.parse(response.body()).get("refresh_token").text();
    if (refresh == null || refresh.isBlank()) {
      throw new IllegalStateException("Spotify token exchange returned no refresh token");
    }
    return refresh;
  }

  private static void respond(HttpExchange exchange, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  static String callbackError(String query, String expectedState) {
    String receivedState = queryParam(query, "state");
    if (!expectedState.equals(receivedState)) {
      return "state mismatch";
    }
    String receivedError = queryParam(query, "error");
    if (receivedError != null) {
      return "authorization denied: " + receivedError;
    }
    String receivedCode = queryParam(query, "code");
    if (receivedCode == null || receivedCode.isBlank()) {
      return "missing code";
    }
    return null;
  }

  private static String queryParam(String query, String name) {
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  private static String randomToken(int bytes) {
    byte[] data = new byte[bytes];
    new SecureRandom().nextBytes(data);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
