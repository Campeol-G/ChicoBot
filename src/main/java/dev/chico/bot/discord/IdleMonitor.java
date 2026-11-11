package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disconnects after idleness: empty queue/channel or a paused track. */
public final class IdleMonitor {

  private static final Logger log = LoggerFactory.getLogger(IdleMonitor.class);

  private final JDA jda;
  private final PlaybackService playback;
  private final long idleDisconnectS;
  private final long pausedDisconnectS;
  private final Clock clock;
  private final Map<Long, IdleTracker> trackers = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "idle-monitor");
            thread.setDaemon(true);
            return thread;
          });

  public IdleMonitor(
      JDA jda, PlaybackService playback, long idleDisconnectS, long pausedDisconnectS) {
    this(jda, playback, idleDisconnectS, pausedDisconnectS, Clock.systemUTC());
  }

  IdleMonitor(
      JDA jda,
      PlaybackService playback,
      long idleDisconnectS,
      long pausedDisconnectS,
      Clock clock) {
    this.jda = jda;
    this.playback = playback;
    this.idleDisconnectS = idleDisconnectS;
    this.pausedDisconnectS = pausedDisconnectS;
    this.clock = clock;
  }

  public void start() {
    scheduler.scheduleAtFixedRate(this::checkAll, 60, 60, TimeUnit.SECONDS);
  }

  void checkAll() {
    for (Guild guild : jda.getGuilds()) {
      try {
        check(guild);
      } catch (RuntimeException e) {
        log.warn("Idle check failed", e);
      }
    }
  }

  private void check(Guild guild) {
    var audio = guild.getAudioManager();
    long guildId = guild.getIdLong();
    IdleTracker tracker =
        trackers.computeIfAbsent(
            guildId, id -> new IdleTracker(idleDisconnectS, pausedDisconnectS));
    IdleTracker.Action action =
        tracker.tick(
            audio.getConnectedChannel() != null,
            channelEmpty(guild),
            playback.isPaused(guildId),
            playback.isIdle(guildId),
            clock.millis());
    if (action == IdleTracker.Action.DISCONNECT) {
      playback.leave(guildId);
      trackers.remove(guildId);
    }
  }

  private boolean channelEmpty(Guild guild) {
    var channel = guild.getAudioManager().getConnectedChannel();
    if (channel == null) {
      return true;
    }
    return channel.getMembers().stream().noneMatch(member -> !member.getUser().isBot());
  }
}
