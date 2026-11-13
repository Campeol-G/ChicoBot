package dev.chico.bot.discord;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** `/help`: one ephemeral onboarding embed. */
public final class HelpCommand extends ListenerAdapter {

  static final String COMMAND = "help";
  private static final Logger log = LoggerFactory.getLogger(HelpCommand.class);

  static CommandData commandData() {
    return Commands.slash(COMMAND, "Show the commands and how to import a playlist");
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    if (!COMMAND.equals(event.getName())) {
      return;
    }
    event
        .replyEmbeds(Embeds.help())
        .setEphemeral(true)
        .queue(null, failure -> log.warn("help reply failed", failure));
  }
}
