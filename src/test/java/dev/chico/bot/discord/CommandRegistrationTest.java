package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandRegistrationTest {

  @Test
  void clearsEveryGuildScopeExceptTheActiveDevGuild() {
    assertEquals(
        List.of(1L, 3L),
        CommandRegistration.guildsToClear(List.of(1L, 2L, 3L), Optional.of(2L)));
    assertEquals(
        List.of(1L, 2L),
        CommandRegistration.guildsToClear(List.of(1L, 2L), Optional.empty()));
  }
}
