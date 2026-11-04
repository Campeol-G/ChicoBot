package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-guild queue. Starts the next track when the current one ends. */
public final class TrackScheduler extends AudioEventAdapter {

  private static final Logger log = LoggerFactory.getLogger(TrackScheduler.class);

  private final AudioPlayer player;
  private final TrackQueue<AudioTrack> queue;

  public TrackScheduler(AudioPlayer player, int maxQueue) {
    this.player = player;
    this.queue = new TrackQueue<>(maxQueue);
  }

  public boolean queue(AudioTrack track) {
    if (player.startTrack(track, true)) {
      return true;
    }
    return queue.add(track);
  }

  public void nextTrack() {
    Optional<AudioTrack> next = queue.poll();
    next.ifPresent(track -> player.startTrack(track, false));
  }

  public TrackQueue<AudioTrack> queueView() {
    return queue;
  }

  @Override
  public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
    if (endReason.mayStartNext) {
      nextTrack();
    }
  }

  @Override
  public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
    log.warn("Track failed, skipping to next: {}", track.getInfo().title, exception);
    nextTrack();
  }
}
