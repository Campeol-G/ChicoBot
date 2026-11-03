package dev.chico.bot.discord;

import dev.chico.bot.config.Config;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

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
            .addEventListeners(new PingCommand(config))
            .build();
    jda.awaitReady();
    return jda;
  }
}
