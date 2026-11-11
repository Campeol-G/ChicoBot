package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.nio.ByteBuffer;
import net.dv8tion.jda.api.audio.AudioSendHandler;

/** Bridges Lavaplayer frames to JDA voice. Only this audio class may import JDA. */
public final class AudioPlayerSendHandler implements AudioSendHandler {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AudioPlayerSendHandler.class);
  private static final long WINDOW_MS = 60_000;
  private static final int WARN_GAP_PERCENT = 10;

  private final AudioPlayer player;
  private AudioFrame lastFrame;

  // Counts only, never titles: titles are user content.
  private long polls;
  private long gaps;
  private long consecutiveNull;
  private long maxConsecutiveNull;
  private long lastCallNanos;
  private long maxIntervalMs;
  private long windowStartMs = System.currentTimeMillis();

  public AudioPlayerSendHandler(AudioPlayer player) {
    this.player = player;
  }

  @Override
  public boolean canProvide() {
    boolean active = player.getPlayingTrack() != null && !player.isPaused();
    long nowNanos = System.nanoTime();
    if (lastCallNanos != 0 && active) {
      long intervalMs = (nowNanos - lastCallNanos) / 1_000_000;
      if (intervalMs > maxIntervalMs) {
        maxIntervalMs = intervalMs;
      }
    }
    lastCallNanos = nowNanos;
    lastFrame = player.provide();
    if (active) {
      polls++;
      if (lastFrame == null) {
        gaps++;
        consecutiveNull++;
        if (consecutiveNull > maxConsecutiveNull) {
          maxConsecutiveNull = consecutiveNull;
        }
      } else {
        consecutiveNull = 0;
      }
      long nowMs = System.currentTimeMillis();
      if (nowMs - windowStartMs >= WINDOW_MS) {
        int gapPct = polls == 0 ? 0 : (int) (gaps * 100 / polls);
        if (gapPct > WARN_GAP_PERCENT) {
          log.warn(
              "audio health polls={} gaps={} gapPct={} maxConsecNull={} maxIntervalMs={}",
              polls, gaps, gapPct, maxConsecutiveNull, maxIntervalMs);
        } else {
          log.debug(
              "audio health polls={} gaps={} gapPct={} maxConsecNull={} maxIntervalMs={}",
              polls, gaps, gapPct, maxConsecutiveNull, maxIntervalMs);
        }
        polls = 0;
        gaps = 0;
        maxConsecutiveNull = 0;
        maxIntervalMs = 0;
        windowStartMs = nowMs;
      }
    } else {
      consecutiveNull = 0;
    }
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
