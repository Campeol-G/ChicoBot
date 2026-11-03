package dev.chico.bot.source;

import dev.lavalink.youtube.YoutubeAudioSourceManager;

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
    YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager();
    youtube.useOauth2(null, false);

    long deadline = System.currentTimeMillis() + 600_000L;
    while (System.currentTimeMillis() < deadline) {
      String token = youtube.getOauth2RefreshToken();
      if (token != null && !token.isBlank()) {
        System.out.println("OAUTH DONE. Store this refresh token as YT_REFRESH_TOKEN "
            + "in ~/.config/chicos-bot/env (mode 600). Never commit or share it.");
        System.out.println("YT_REFRESH_TOKEN=" + token);
        System.exit(0);
      }
      Thread.sleep(5_000L);
    }
    System.err.println("OAUTH TIMEOUT: no authorization within 10 minutes. Run again to retry.");
    System.exit(1);
  }
}
