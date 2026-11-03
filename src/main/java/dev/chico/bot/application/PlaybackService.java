package dev.chico.bot.application;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.chico.bot.audio.AudioPlayerSendHandler;
import dev.chico.bot.audio.GuildMusicManager;
import dev.chico.bot.config.Config;
import dev.chico.bot.source.PatchedTvClient;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spike playback: loads one URL and enqueues it. URL policy and the resolver
 * arrive in Phase 2.
 */
public final class PlaybackService {

  private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

  private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
  private final Map<Long, GuildMusicManager> managers = new ConcurrentHashMap<>();
  private final VoiceConnector connector;

  public PlaybackService(VoiceConnector connector, Config config) {
    this.connector = connector;
    playerManager.registerSourceManager(createYoutubeSource(config));
    playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
  }

  private static YoutubeAudioSourceManager createYoutubeSource(Config config) {
    YoutubeSourceOptions options = new YoutubeSourceOptions();
    // NOTA: remote cipher until a youtube-source release fixes the local
    // signature cipher for current player scripts (upstream issue #241).
    // Recheck by clearing YT_CIPHER_URL: if login-walled videos play, drop this.
    config.ytCipherUrl().ifPresent(url ->
        options.setRemoteCipher(url, config.ytCipherPassword(), "chicos-bot"));
    Optional<String> refreshToken =
        config.ytRefreshToken().filter(token -> !token.isBlank());
    boolean authenticated = refreshToken.isPresent();
    // Tv is the only OAuth-capable client; other clients keep metadata and search working.
    YoutubeAudioSourceManager youtube =
        authenticated
            ? new YoutubeAudioSourceManager(
                options,
                new Music(), new AndroidVr(), new Web(), new WebEmbedded(), new PatchedTvClient())
            : new YoutubeAudioSourceManager(
                options, YoutubeAudioSourceManager.DEFAULT_CLIENTS);
    if (authenticated) {
      youtube.useOauth2(refreshToken.orElseThrow(), true);
      log.info("YouTube OAuth enabled");
    } else {
      log.info("YouTube OAuth disabled, unauthenticated access");
    }
    return youtube;
  }

  public AudioPlayerSendHandler sendHandler(long guildId) {
    return musicManager(guildId).sendHandler();
  }

  public void playUrl(long guildId, long voiceChannelId, String url) {
    GuildMusicManager music = musicManager(guildId);
    connector.connect(guildId, voiceChannelId);
    playerManager.loadItem(url, new AudioLoadResultHandler() {
      @Override
      public void trackLoaded(AudioTrack track) {
        music.scheduler().queue(track);
      }

      @Override
      public void playlistLoaded(AudioPlaylist playlist) {
        AudioTrack first = playlist.getSelectedTrack() != null
            ? playlist.getSelectedTrack()
            : playlist.getTracks().get(0);
        music.scheduler().queue(first);
      }

      @Override
      public void noMatches() {
        log.warn("No matches for url playback");
      }

      @Override
      public void loadFailed(FriendlyException exception) {
        log.warn("Load failed for url playback", exception);
      }
    });
  }

  private GuildMusicManager musicManager(long guildId) {
    return managers.computeIfAbsent(guildId, id -> new GuildMusicManager(playerManager));
  }
}
