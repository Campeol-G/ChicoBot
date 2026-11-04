package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Disconnects after idleness: empty queue or an empty voice channel. */
public final class IdleMonitor {

  private static final Logger log = LoggerFactory.getLogger(IdleMonitor.class);

  private final JDA jda;
  private final PlaybackService playback;
  private final long idleDisconnectS;
  private final Map<Long, Long> idleSince = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "idle-monitor");
            thread.setDaemon(true);
            return thread;
          });

  public IdleMonitor(JDA jda, PlaybackService playback, long idleDisconnectS) {
    this.jda = jda;
    this.playback = playback;
    this.idleDisconnectS = idleDisconnectS;
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
    if (audio.getConnectedChannel() == null) {
      idleSince.remove(guildId);
      return;
    }
    if (playback.isIdle(guildId) || channelEmpty(guild)) {
      long since = idleSince.computeIfAbsent(guildId, id -> System.currentTimeMillis());
      if (System.currentTimeMillis() - since >= idleDisconnectS * 1000L) {
        playback.leave(guildId);
        idleSince.remove(guildId);
      }
    } else {
      idleSince.remove(guildId);
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
