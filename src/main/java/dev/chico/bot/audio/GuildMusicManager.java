package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.chico.bot.source.TrackCandidate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Holds the player, scheduler and send handler of one guild. */
public final class GuildMusicManager {

  private final AudioPlayer player;
  private final TrackScheduler scheduler;
  private final AudioPlayerSendHandler sendHandler;
  private final AtomicLong nextItemId = new AtomicLong();

  public GuildMusicManager(AudioPlayerManager manager, int queueMax) {
    this(manager, queueMax, () -> {
    });
  }

  public GuildMusicManager(AudioPlayerManager manager, int queueMax, Runnable onChange) {
    this.player = manager.createPlayer();
    this.scheduler = new TrackScheduler(player, queueMax, onChange);
    this.player.addListener(scheduler);
    this.sendHandler = new AudioPlayerSendHandler(player);
  }

  public AudioPlayer player() {
    return player;
  }

  public TrackScheduler scheduler() {
    return scheduler;
  }

  public AudioPlayerSendHandler sendHandler() {
    return sendHandler;
  }

  /**
   * Adds the batch in order until the queue is full. The guild lock keeps
   * concurrent batches from interleaving. Every accepted item gets a fresh
   * queue identity and the batch owner.
   *
   * @return how many tracks were accepted
   */
  public synchronized int enqueueAll(List<TrackCandidate> candidates, long addedBy) {
    int accepted = 0;
    for (TrackCandidate candidate : candidates) {
      TrackCandidate owned =
          candidate.withQueueIdentity(nextItemId.incrementAndGet(), addedBy);
      if (!scheduler.queue(owned)) {
        break;
      }
      accepted++;
    }
    return accepted;
  }

  public synchronized int enqueueAll(List<TrackCandidate> candidates) {
    return enqueueAll(candidates, 0L);
  }
}
