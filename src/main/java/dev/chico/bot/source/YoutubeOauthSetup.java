package dev.chico.bot.source;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * One-shot YouTube OAuth device flow. Prints the verification URL and code,
 * waits for authorization in the browser, then prints the refresh token.
 *
 * <p>Run: {@code java -cp target/chicos-bot.jar dev.chico.bot.source.YoutubeOauthSetup}
 * then store the printed token as {@code YT_REFRESH_TOKEN} in the env file.
 * Never commit or share the token. Use a burner Google account, not your main.
 */
public final class YoutubeOauthSetup {

  private YoutubeOauthSetup() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 1) {
      System.err.println("usage: YoutubeOauthSetup [refresh-token-file]");
      System.exit(2);
    }
    YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager();
    youtube.useOauth2(null, false);

    long deadline = System.currentTimeMillis() + 600_000L;
    while (System.currentTimeMillis() < deadline) {
      String token = youtube.getOauth2RefreshToken();
      if (token != null && !token.isBlank()) {
        if (args.length == 1) {
          writeToken(Path.of(args[0]), token);
          System.out.println(
              "OAUTH DONE. Refresh token saved to " + args[0] + " (mode 600).");
        } else {
          System.out.println("OAUTH DONE. Store this refresh token as YT_REFRESH_TOKEN "
              + "in ~/.config/chicos-bot/env (mode 600). Never commit or share it.");
          System.out.println("YT_REFRESH_TOKEN=" + token);
        }
        System.exit(0);
      }
      Thread.sleep(5_000L);
    }
    System.err.println("OAUTH TIMEOUT: no authorization within 10 minutes. Run again to retry.");
    System.exit(1);
  }

  static void writeToken(Path file, String token) throws IOException {
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
        "YT_REFRESH_TOKEN=" + token + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // Linux is the only supported platform for this bot.
    }
  }
}
