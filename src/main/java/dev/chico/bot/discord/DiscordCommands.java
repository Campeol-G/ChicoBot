package dev.chico.bot.discord;

import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

final class DiscordCommands {

  private DiscordCommands() {
  }

  static List<CommandData> all() {
    List<CommandData> commands = new ArrayList<>(MusicCommands.commandData());
    commands.add(HelpCommand.commandData());
    commands.add(PingCommand.commandData());
    commands.add(ImageContextMenu.commandData());
    return List.copyOf(commands);
  }
}
