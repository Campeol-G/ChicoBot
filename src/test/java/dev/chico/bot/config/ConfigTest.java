package dev.chico.bot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    assertEquals(0.35, config.matchThreshold());
    assertEquals(50, config.importMaxTracks());
    assertEquals(50, config.queueMax());
    assertTrue(config.devGuildId().isEmpty());
  }

  @Test
  void defaultsAreRecallFirstCaps() {
    Config config = new Config(envWithToken());

    assertEquals(50, config.queueMax());
    assertEquals(50, config.importMaxTracks());
    assertEquals(0.35, config.matchThreshold());
  }

  @Test
  void parallelSearchDefaultsOn() {
    Config config = new Config(envWithToken());
    assertTrue(config.parallelSearch());
  }

  @Test
  void parallelSearchCanBeDisabled() {
    Config config = new Config(Map.of(
        "DISCORD_TOKEN", "test-token-placeholder",
        "PARALLEL_SEARCH", "false"));
    assertFalse(config.parallelSearch());
  }

  @Test
  void parallelSearchRejectsInvalidValue() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(Map.of(
            "DISCORD_TOKEN", "test-token-placeholder",
            "PARALLEL_SEARCH", "yes")));
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

  @Test
  void negativeQueueMaxRejectedWithKeyName() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> new Config(Map.of(
            "DISCORD_TOKEN", "test-token-placeholder", "QUEUE_MAX", "-1")));

    assertTrue(ex.getMessage().contains("QUEUE_MAX"), "got: " + ex.getMessage());
  }

  @Test
  void zeroQueueMaxStillBoots() {
    assertEquals(
        0,
        new Config(Map.of(
            "DISCORD_TOKEN", "test-token-placeholder", "QUEUE_MAX", "0")).queueMax());
  }

  @Test
  void nonNumericValueRejectedWithKeyName() {
    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> new Config(Map.of(
            "DISCORD_TOKEN", "test-token-placeholder", "OCR_TIMEOUT_MS", "abc")));

    assertTrue(ex.getMessage().contains("OCR_TIMEOUT_MS"), "got: " + ex.getMessage());
  }

  @Test
  void cipherUrlWithoutPasswordRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(Map.of(
            "DISCORD_TOKEN", "test-token-placeholder",
            "YT_CIPHER_URL", "http://localhost:8001")));
  }

  @Test
  void ytRefreshTokenEmptyByDefault() {
    Config config = new Config(envWithToken());

    assertTrue(config.ytRefreshToken().isEmpty());
  }

  @Test
  void ytRefreshTokenFromEnv() {
    Config config = new Config(Map.of(
        "DISCORD_TOKEN", "test-token-placeholder",
        "YT_REFRESH_TOKEN", "refresh-token-placeholder"));

    assertTrue(config.ytRefreshToken().isPresent());
    assertEquals("refresh-token-placeholder", config.ytRefreshToken().orElseThrow());
  }

  @Test
  void blankYtRefreshTokenTreatedAsEmpty() {
    Config config = new Config(Map.of(
        "DISCORD_TOKEN", "test-token-placeholder",
        "YT_REFRESH_TOKEN", "   "));

    assertTrue(config.ytRefreshToken().isEmpty());
  }

  @Test
  void ytCipherDisabledByDefault() {
    Config config = new Config(envWithToken());

    assertTrue(config.ytCipherUrl().isEmpty());
    assertEquals("", config.ytCipherPassword());
  }

  @Test
  void ytCipherFromEnv() {
    Config config = new Config(Map.of(
        "DISCORD_TOKEN", "test-token-placeholder",
        "YT_CIPHER_URL", "http://localhost:8001",
        "YT_CIPHER_PASSWORD", "cipher-password-placeholder"));

    assertEquals("http://localhost:8001", config.ytCipherUrl().orElseThrow());
    assertEquals("cipher-password-placeholder", config.ytCipherPassword());
  }

  @Test
  void blankYtCipherUrlTreatedAsEmpty() {
    Config config = new Config(Map.of(
        "DISCORD_TOKEN", "test-token-placeholder",
        "YT_CIPHER_URL", "   "));

    assertTrue(config.ytCipherUrl().isEmpty());
  }
}
