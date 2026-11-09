package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.chico.bot.source.TrackCandidate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-guild queue. The current track follows playback events; transitions
 * reenter through onTrackEnd, so state changes are guarded, not assumed.
 */
public final class TrackScheduler extends AudioEventAdapter {

  private static final Logger log = LoggerFactory.getLogger(TrackScheduler.class);

  private final AudioPlayer player;
  private final TrackQueue<TrackCandidate> queue;
  private volatile TrackCandidate current;
  private volatile TrackCandidate pending;
  private volatile boolean advancing;

  public TrackScheduler(AudioPlayer player, int maxQueue) {
    this.player = player;
    this.queue = new TrackQueue<>(maxQueue);
  }

  public synchronized boolean queue(TrackCandidate candidate) {
    advancing = true;
    try {
      if (player.startTrack(candidate.track(), true)) {
        current = candidate;
        pending = candidate;
        return true;
      }
    } finally {
      advancing = false;
    }
    return queue.add(candidate);
  }

  public synchronized void nextTrack() {
    if (advancing) {
      return;
    }
    Optional<TrackCandidate> next = queue.poll();
    if (next.isEmpty()) {
      player.stopTrack();
      current = null;
      pending = null;
      return;
    }
    pending = next.orElseThrow();
    advancing = true;
    try {
      player.startTrack(pending.track(), false);
    } finally {
      advancing = false;
    }
    current = pending;
  }

  public Optional<TrackCandidate> current() {
    return Optional.ofNullable(current);
  }

  public TrackQueue<TrackCandidate> queueView() {
    return queue;
  }

  @Override
  public synchronized void onTrackStart(AudioPlayer player, AudioTrack track) {
    if (pending != null && pending.track() == track) {
      current = pending;
    }
  }

  @Override
  public synchronized void onTrackEnd(
      AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
    if (pending != null && pending.track() != track) {
      return;
    }
    log.info(
        "track ended reason={} mayStartNext={} title={}",
        endReason, endReason.mayStartNext, track.getInfo().title);
    if (!endReason.mayStartNext) {
      current = null;
      pending = null;
      return;
    }
    if (current != null && current.track() == track) {
      nextTrack();
    }
  }

  @Override
  public synchronized void onTrackException(
      AudioPlayer player, AudioTrack track, FriendlyException exception) {
    log.warn("Track failed, skipping to next: {}", track.getInfo().title, exception);
    if (pending != null && pending.track() != track) {
      return;
    }
    if (current != null && current.track() == track) {
      nextTrack();
    }
  }
}
