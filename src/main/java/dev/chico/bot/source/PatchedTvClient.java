package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.clients.ClientConfig;
import dev.lavalink.youtube.clients.Tv;

/**
 * Tv client with the User-Agent YouTube accepts for authenticated playback.
 *
 * <p>NOTA: youtube-source 1.18.2 ships Tv with a Cobalt User-Agent that YouTube
 * rejects server-side (UNPLAYABLE "The page needs to be reloaded", upstream issue
 * #226, fixed upstream by switching Tv to a PlayStation User-Agent). Until a
 * release with that fix is adopted, this subclass applies the same values.
 * Remove it when bumping youtube-source past the fix.
 */
public final class PatchedTvClient extends Tv {

  static final String USER_AGENT =
      "Mozilla/5.0 (PlayStation; PlayStation 4/12.00)"
          + " AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15";
  static final String CLIENT_VERSION = "7.20250319.10.00";

  @Override
  protected ClientConfig getBaseClientConfig(HttpInterface httpInterface) {
    return new ClientConfig()
        .withClientName("TVHTML5")
        .withUserAgent(USER_AGENT)
        .withClientField("clientVersion", CLIENT_VERSION);
  }
}
