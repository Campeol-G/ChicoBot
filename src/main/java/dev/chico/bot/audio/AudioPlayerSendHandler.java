package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.nio.ByteBuffer;
import net.dv8tion.jda.api.audio.AudioSendHandler;

/** Bridges Lavaplayer frames to JDA voice. Only this audio class may import JDA. */
public final class AudioPlayerSendHandler implements AudioSendHandler {

  private final AudioPlayer player;
  private AudioFrame lastFrame;

  public AudioPlayerSendHandler(AudioPlayer player) {
    this.player = player;
  }

  @Override
  public boolean canProvide() {
    lastFrame = player.provide();
    return lastFrame != null;
  }

  @Override
  public ByteBuffer provide20MsAudio() {
    AudioFrame frame = lastFrame;
    lastFrame = null;
    return frame == null ? null : ByteBuffer.wrap(frame.getData());
  }

  @Override
  public boolean isOpus() {
    return true;
  }
}
