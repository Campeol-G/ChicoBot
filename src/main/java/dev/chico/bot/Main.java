package dev.chico.bot;

import dev.chico.bot.config.Config;
import dev.chico.bot.discord.Bot;

/** Application entry point. */
public final class Main {

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    Config config = new Config(System.getenv());
    Bot bot = new Bot(config);
    bot.start();
  }
}
