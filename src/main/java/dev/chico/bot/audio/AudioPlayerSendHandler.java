package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.nio.ByteBuffer;
import net.dv8tion.jda.api.audio.AudioSendHandler;

/** Bridges Lavaplayer frames to JDA voice. Only this audio class may import JDA. */
public final class AudioPlayerSendHandler implements AudioSendHandler {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AudioPlayerSendHandler.class);
  // SPIKE: throwaway gap/cadence counter, revert after measurement.
  private static final long LOG_EVERY_MS = 60_000;
  private long polls;
  private long gaps;
  private long consecutiveNull;
  private long maxConsecutiveNull;
  private long lastCallNanos;
  private long maxIntervalMs;
  private long windowStartMs = System.currentTimeMillis();

  private final AudioPlayer player;
  private AudioFrame lastFrame;

  public AudioPlayerSendHandler(AudioPlayer player) {
    this.player = player;
  }

  @Override
  public boolean canProvide() {
    // SPIKE: count only while actually playing; paused/idle polls silence forever.
    boolean active =
        player.getPlayingTrack() != null && !player.isPaused();
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
      if (nowMs - windowStartMs >= LOG_EVERY_MS) {
        // Counts only, never titles: titles are user content.
        log.info(
            "spike audio polls={} gaps={} gapPct={} maxConsecNull={} maxIntervalMs={}",
            polls,
            gaps,
            polls == 0 ? 0 : gaps * 100 / polls,
            maxConsecutiveNull,
            maxIntervalMs);
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
