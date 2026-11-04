package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.chico.bot.source.TrackCandidate;
import java.util.List;

/** Holds the player, scheduler and send handler of one guild. */
public final class GuildMusicManager {

  private final AudioPlayer player;
  private final TrackScheduler scheduler;
  private final AudioPlayerSendHandler sendHandler;

  public GuildMusicManager(AudioPlayerManager manager, int queueMax) {
    this.player = manager.createPlayer();
    this.scheduler = new TrackScheduler(player, queueMax);
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
   * concurrent batches from interleaving.
   *
   * @return how many tracks were accepted
   */
  public synchronized int enqueueAll(List<TrackCandidate> candidates) {
    int accepted = 0;
    for (TrackCandidate candidate : candidates) {
      if (!scheduler.queue(candidate)) {
        break;
      }
      accepted++;
    }
    return accepted;
  }
}
