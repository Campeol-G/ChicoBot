package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.application.VoiceConnector;
import dev.chico.bot.audio.AudioPlayerSendHandler;
import java.util.function.Function;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;

/** JDA implementation of the voice port. Resolves IDs to JDA objects here. */
public final class JdaVoiceConnector implements VoiceConnector {

  private final JDA jda;
  private final Function<Long, AudioPlayerSendHandler> handlerProvider;

  public JdaVoiceConnector(JDA jda, Function<Long, AudioPlayerSendHandler> handlerProvider) {
    this.jda = jda;
    this.handlerProvider = handlerProvider;
  }

  @Override
  public void connect(long guildId, long voiceChannelId) {
    Guild guild = jda.getGuildById(guildId);
    if (guild == null) {
      return;
    }
    VoiceChannel channel = guild.getVoiceChannelById(voiceChannelId);
    if (channel == null) {
      return;
    }
    if (!guild
        .getSelfMember()
        .hasPermission(
            channel,
            net.dv8tion.jda.api.Permission.VOICE_CONNECT,
            net.dv8tion.jda.api.Permission.VOICE_SPEAK)) {
      return;
    }
    AudioManager audio = guild.getAudioManager();
    audio.setSendingHandler(handlerProvider.apply(guildId));
    audio.openAudioConnection(channel);
  }

  @Override
  public void disconnect(long guildId) {
    Guild guild = jda.getGuildById(guildId);
    if (guild != null) {
      guild.getAudioManager().closeAudioConnection();
    }
  }
}
