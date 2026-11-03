package dev.chico.bot.config;

import java.util.Map;
import java.util.Optional;

/** Bot configuration loaded from environment variables with spec defaults. */
public final class Config {

  private final Map<String, String> env;

  public Config(Map<String, String> env) {
    this.env = Map.copyOf(env);
    if (tokenValue().isBlank()) {
      throw new IllegalArgumentException(
          "DISCORD_TOKEN is not set. Load it from ~/.config/chicos-bot/env (see README)");
    }
    devGuildId();
  }

  public String token() {
    return tokenValue();
  }

  public Optional<Long> devGuildId() {
    String raw = env.getOrDefault("DEV_GUILD_ID", "").strip();
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseUnsignedLong(raw));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("DEV_GUILD_ID must be a numeric guild id");
    }
  }

  public String ocrLangs() {
    return env.getOrDefault("OCR_LANGS", "por+eng");
  }

  public int ocrTimeoutMs() {
    return parseInt("OCR_TIMEOUT_MS", 8000);
  }

  public int ocrMaxConcurrency() {
    return parseInt("OCR_MAX_CONCURRENCY", 2);
  }

  public long imgMaxBytes() {
    return parseLong("IMG_MAX_BYTES", 8388608L);
  }

  public long imgMaxPixels() {
    return parseLong("IMG_MAX_PIXELS", 16777216L);
  }

  public int importMaxTracks() {
    return parseInt("IMPORT_MAX_TRACKS", 25);
  }

  public int importSessionTtlS() {
    return parseInt("IMPORT_SESSION_TTL_S", 300);
  }

  public int cooldownUserS() {
    return parseInt("COOLDOWN_USER_S", 15);
  }

  public int queueMax() {
    return parseInt("QUEUE_MAX", 200);
  }

  public int idleDisconnectS() {
    return parseInt("IDLE_DISCONNECT_S", 300);
  }

  public int resolveTimeoutMs() {
    return parseInt("RESOLVE_TIMEOUT_MS", 8000);
  }

  public int resolveConcurrency() {
    return parseInt("RESOLVE_CONCURRENCY", 4);
  }

  public int sourceFailureThreshold() {
    return parseInt("SOURCE_FAILURE_THRESHOLD", 3);
  }

  public int sourceDegradedCooldownS() {
    return parseInt("SOURCE_DEGRADED_COOLDOWN_S", 300);
  }

  public int playCooldownS() {
    return parseInt("PLAY_COOLDOWN_S", 5);
  }

  public double matchThreshold() {
    return parseDouble("MATCH_THRESHOLD", 0.0);
  }

  public String logLevel() {
    return env.getOrDefault("LOG_LEVEL", "INFO");
  }

  private String tokenValue() {
    return env.getOrDefault("DISCORD_TOKEN", "").strip();
  }

  private int parseInt(String key, int fallback) {
    String raw = env.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    return Integer.parseInt(raw.strip());
  }

  private long parseLong(String key, long fallback) {
    String raw = env.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    return Long.parseLong(raw.strip());
  }

  private double parseDouble(String key, double fallback) {
    String raw = env.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    return Double.parseDouble(raw.strip());
  }
}
