package dev.chico.bot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

  private static Map<String, String> envWithToken() {
    return Map.of("DISCORD_TOKEN", "test-token-placeholder");
  }

  @Test
  void defaultsAppliedWhenEnvEmpty() {
    Config config = new Config(envWithToken());

    assertEquals(8388608L, config.imgMaxBytes());
    assertEquals(0.0, config.matchThreshold());
    assertEquals(25, config.importMaxTracks());
    assertTrue(config.devGuildId().isEmpty());
  }

  @Test
  void envOverridesDefaults() {
    Config config = new Config(Map.of(
        "DISCORD_TOKEN", "test-token-placeholder",
        "QUEUE_MAX", "10",
        "OCR_LANGS", "eng"));

    assertEquals(10, config.queueMax());
    assertEquals("eng", config.ocrLangs());
  }

  @Test
  void devGuildIdParsed() {
    Config config = new Config(Map.of(
        "DISCORD_TOKEN", "test-token-placeholder",
        "DEV_GUILD_ID", "123456789012345678"));

    assertTrue(config.devGuildId().isPresent());
    assertEquals(123456789012345678L, config.devGuildId().orElseThrow());
  }

  @Test
  void invalidDevGuildIdRejected() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> new Config(Map.of(
            "DISCORD_TOKEN", "test-token-placeholder",
            "DEV_GUILD_ID", "abc")));

    assertTrue(ex.getMessage() != null && !ex.getMessage().contains("abc"));
  }

  @Test
  void missingTokenRejected() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> new Config(Map.of()));

    assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank());
  }

  @Test
  void missingTokenMessagePointsToEnvFile() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> new Config(Map.of()));

    assertTrue(ex.getMessage().contains("~/.config/chicos-bot/env"),
        "message should tell where the token comes from, got: " + ex.getMessage());
  }
}
