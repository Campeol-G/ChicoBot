package dev.chico.bot.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

/** Minimal slash command used to verify the bot is online. */
public final class PingCommand extends ListenerAdapter {

  static CommandData commandData() {
    return Commands.slash("ping", "Replies with pong");
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    if ("ping".equals(event.getName())) {
      event.reply("Pong").queue();
    }
  }
}
