package dev.chico.bot.discord;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import dev.chico.bot.application.ImageDownloader;
import dev.chico.bot.application.ImportFromImage;
import dev.chico.bot.application.ImportSession;
import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.config.Config;
import dev.chico.bot.ocr.ImagePreprocessor;
import dev.chico.bot.ocr.TesseractCliEngine;
import dev.chico.bot.parser.DefaultTrackLineParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Discord client bootstrap. */
public final class Bot {

  private static final Logger log = LoggerFactory.getLogger(Bot.class);

  private final Config config;

  public Bot(Config config) {
    this.config = config;
  }

  public JDA start() throws InterruptedException {
    JDA jda =
        JDABuilder.createLight(
                config.token(),
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.MESSAGE_CONTENT)
            .enableCache(CacheFlag.VOICE_STATE)
            .setAudioModuleConfig(
                new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()))
            .addEventListeners(new PingCommand())
            .build();
    jda.awaitReady();

    AtomicReference<PlaybackService> playbackRef = new AtomicReference<>();
    JdaVoiceConnector connector =
        new JdaVoiceConnector(jda, guildId -> playbackRef.get().sendHandler(guildId));
    playbackRef.set(new PlaybackService(connector, config));
    QueueLive liveQueue = new QueueLive(jda, playbackRef.get());
    MusicCommands commands = new MusicCommands(playbackRef.get(), liveQueue);
    jda.addEventListener(commands);
    jda.addEventListener(new HelpCommand());
    playbackRef.get().setQueueChangeListener(liveQueue::refreshIfPresent);
    playbackRef.get().setTrackFailureListener(liveQueue::announceFailure);
    ImportSession.Store sessions =
        new ImportSession.Store(
            java.time.Duration.ofSeconds(config.importSessionTtlS()),
            java.time.Clock.systemUTC());
    ImportFromImage importFlow =
        new ImportFromImage(
            config,
            new ImageDownloader(config.imgMaxBytes()),
            new TesseractCliEngine(
                config.ocrLangs(), java.time.Duration.ofMillis(config.ocrTimeoutMs())),
            new DefaultTrackLineParser(),
            new ImagePreprocessor(config.imgMaxPixels()),
            sessions);
    jda.addEventListener(new MessageListener(playbackRef.get(), importFlow, config));
    jda.addEventListener(new PreviewButtons(sessions, playbackRef.get(), config));
    jda.addEventListener(new PlaylistConfirm(playbackRef.get()));
    jda.addEventListener(new ImageContextMenu(importFlow, config));
    new IdleMonitor(
            jda, playbackRef.get(), config.idleDisconnectS(), config.pausedDisconnectS())
        .start();
    registerCommands(jda);

    return jda;
  }

  private void registerCommands(JDA jda) {
    List<CommandData> commands = DiscordCommands.all();
    if (config.devGuildId().isPresent()) {
      Guild guild = jda.getGuildById(config.devGuildId().orElseThrow());
      if (guild == null) {
        // POR QUE: falling through would register dev commands globally.
        throw new IllegalStateException(
            "DEV_GUILD_ID does not match a guild this bot is in; refusing to register globally");
      }
      guild
          .updateCommands()
          .addCommands(commands)
          .queue(
              null,
              failure ->
                  log.warn("guild command update failed guild={}", guild.getId(), failure));
      jda.updateCommands()
          .queue(null, failure -> log.warn("global command clear failed", failure));
      return;
    }
    jda.updateCommands()
        .addCommands(commands)
        .queue(null, failure -> log.warn("global command update failed", failure));
  }
}
