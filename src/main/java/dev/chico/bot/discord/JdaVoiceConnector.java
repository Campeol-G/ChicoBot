package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.application.VoiceConnector;
import dev.chico.bot.audio.AudioPlayerSendHandler;
import java.util.function.Function;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JDA implementation of the voice port. Resolves IDs to JDA objects here. */
public final class JdaVoiceConnector implements VoiceConnector {

  private static final Logger log = LoggerFactory.getLogger(JdaVoiceConnector.class);

  private final JDA jda;
  private final Function<Long, AudioPlayerSendHandler> handlerProvider;

  public JdaVoiceConnector(JDA jda, Function<Long, AudioPlayerSendHandler> handlerProvider) {
    this.jda = jda;
    this.handlerProvider = handlerProvider;
  }

  @Override
  public boolean connect(long guildId, long voiceChannelId) {
    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      log.warn("voice join refused: unknown guild {}", guildId);
      return false;
    }
    VoiceChannel channel = guild.getVoiceChannelById(voiceChannelId);
    if (channel == null) {
      log.warn("voice join refused: unresolvable channel {} in guild {}", voiceChannelId, guildId);
      return false;
    }
    if (!guild
        .getSelfMember()
        .hasPermission(
            channel,
            net.dv8tion.jda.api.Permission.VOICE_CONNECT,
            net.dv8tion.jda.api.Permission.VOICE_SPEAK)) {
      log.warn("voice join refused: missing VOICE_CONNECT/SPEAK in channel {}", voiceChannelId);
      return false;
    }
    AudioManager audio = guild.getAudioManager();
    audio.setSendingHandler(handlerProvider.apply(guildId));
    audio.openAudioConnection(channel);
    return true;
  }

  @Override
  public void disconnect(long guildId) {
    Guild guild = jda.getGuildById(guildId);
    if (guild != null) {
      guild.getAudioManager().closeAudioConnection();
    }
  }
}
