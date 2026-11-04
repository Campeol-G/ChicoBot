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
    playbackRef.set(new PlaybackService(connector, config));
    MusicCommands commands = new MusicCommands(playbackRef.get());
    jda.addEventListener(commands);
    ImportSession.Store sessions = new ImportSession.Store();
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
    jda.addEventListener(new ImageContextMenu(importFlow, config));
    new IdleMonitor(jda, playbackRef.get(), config.idleDisconnectS()).start();
    registerPlay(jda);

    return jda;
  }

  private void registerPlay(JDA jda) {
    if (config.devGuildId().isPresent()) {
      Guild guild = jda.getGuildById(config.devGuildId().orElseThrow());
      if (guild != null) {
        MusicCommands.register(guild);
        ImageContextMenu.register(guild);
        return;
      }
    }
    MusicCommands.registerGlobal(jda);
    ImageContextMenu.registerGlobal(jda);
  }
}
