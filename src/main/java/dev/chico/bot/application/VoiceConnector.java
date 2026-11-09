package dev.chico.bot.application;

/** Port for connecting to voice. Implemented in the discord package. */
public interface VoiceConnector {
  /** Joins the voice channel; returns false when the join was refused. */
  boolean connect(long guildId, long voiceChannelId);

  void disconnect(long guildId);
}
