package dev.chico.bot.discord;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.config.Config;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import java.util.concurrent.atomic.AtomicReference;

/** Discord client bootstrap. */
public final class Bot {

  private final Config config;

  public Bot(Config config) {
    this.config = config;
  }

  public JDA start() throws InterruptedException {
    JDA jda =
        JDABuilder.createLight(
                config.token(), GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_VOICE_STATES)
            .enableCache(CacheFlag.VOICE_STATE)
            .setAudioModuleConfig(
                new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
            .addEventListeners(new PingCommand(config))
            .build();
    jda.awaitReady();

    AtomicReference<PlaybackService> playbackRef = new AtomicReference<>();
    JdaVoiceConnector connector =
        new JdaVoiceConnector(jda, guildId -> playbackRef.get().sendHandler(guildId));
    playbackRef.set(new PlaybackService(connector));
    MusicCommands commands = new MusicCommands(playbackRef.get());
    jda.addEventListener(commands);
    registerPlay(jda);

    return jda;
  }

  private void registerPlay(JDA jda) {
    if (config.devGuildId().isPresent()) {
      Guild guild = jda.getGuildById(config.devGuildId().orElseThrow());
      if (guild != null) {
        MusicCommands.register(guild);
        return;
      }
    }
    MusicCommands.registerGlobal(jda);
  }
}
