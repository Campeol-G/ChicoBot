package dev.chico.bot.application;

/** Port for connecting to voice. Implemented in the discord package. */
public interface VoiceConnector {
  void connect(long guildId, long voiceChannelId);

  void disconnect(long guildId);
}
