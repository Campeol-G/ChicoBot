package dev.chico.bot.discord;

import dev.chico.bot.config.Config;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/** Minimal slash command used to verify the bot is online. */
public final class PingCommand extends ListenerAdapter {

  private final Config config;

  public PingCommand(Config config) {
    this.config = config;
  }

  @Override
  public void onReady(ReadyEvent event) {
    if (config.devGuildId().isPresent()) {
      Guild guild = event.getJDA().getGuildById(config.devGuildId().orElseThrow());
      if (guild != null) {
        guild.upsertCommand("ping", "Replies with pong").queue();
        return;
      }
    }
    event.getJDA().upsertCommand("ping", "Replies with pong").queue();
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    if ("ping".equals(event.getName())) {
      event.reply("Pong").queue();
    }
  }
}
