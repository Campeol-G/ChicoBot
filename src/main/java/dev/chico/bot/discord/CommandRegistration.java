package dev.chico.bot.discord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

final class CommandRegistration {

  private CommandRegistration() {
  }

  /** Guild scopes to clear: all connected guilds except the active dev guild. */
  static List<Long> guildsToClear(
      Collection<Long> connectedGuildIds, Optional<Long> devGuildId) {
    return connectedGuildIds.stream()
        .filter(id -> devGuildId.isEmpty() || !devGuildId.get().equals(id))
        .toList();
  }
}
