package dev.chico.bot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;

/** Holds the player, scheduler and send handler of one guild. */
public final class GuildMusicManager {

  private final AudioPlayer player;
  private final TrackScheduler scheduler;
  private final AudioPlayerSendHandler sendHandler;

  public GuildMusicManager(AudioPlayerManager manager) {
    this.player = manager.createPlayer();
    this.scheduler = new TrackScheduler(player);
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
}
