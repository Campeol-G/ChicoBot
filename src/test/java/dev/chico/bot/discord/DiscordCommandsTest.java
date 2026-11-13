package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.dv8tion.jda.api.interactions.commands.Command;
import org.junit.jupiter.api.Test;

class DiscordCommandsTest {

  @Test
  void currentListIsCompleteAndUnique() {
    var commands = DiscordCommands.all();
    var names = commands.stream().map(command -> command.getName()).toList();

    assertTrue(
        names.containsAll(
            List.of(
                "play", "pause", "resume", "skip", "stop", "queue", "remove",
                "leave", "nowplaying", "loop", "ping")));
    assertEquals(names.size(), new java.util.HashSet<>(names).size());
    assertTrue(commands.stream().anyMatch(command -> command.getType() == Command.Type.MESSAGE));
  }
}
