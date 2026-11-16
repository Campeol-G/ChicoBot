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
    validate();
  }

  public String token() {
    return tokenValue();
  }

  public Optional<String> ytRefreshToken() {
    String raw = env.getOrDefault("YT_REFRESH_TOKEN", "").strip();
    return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
  }

  public Optional<String> ytCipherUrl() {
    String raw = env.getOrDefault("YT_CIPHER_URL", "").strip();
    return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
  }

  /** Empty is valid for the public no-auth cipher. */
  public String ytCipherPassword() {
    return env.getOrDefault("YT_CIPHER_PASSWORD", "");
  }

  public Optional<Long> devGuildId() {
    String raw = env.getOrDefault("DEV_GUILD_ID", "").strip();
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(raw));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("DEV_GUILD_ID must be a numeric guild id");
    }
  }

  public Optional<String> spotifyClientId() {
    return optional("SPOTIFY_CLIENT_ID");
  }

  public Optional<String> spotifyClientSecret() {
    return optional("SPOTIFY_CLIENT_SECRET");
  }

  public Optional<String> spotifyRefreshToken() {
    return optional("SPOTIFY_REFRESH_TOKEN");
  }

  public Optional<String> appleMusicDeveloperToken() {
    return optional("APPLE_MUSIC_DEVELOPER_TOKEN");
  }

  public String appleMusicStorefront() {
    return env.getOrDefault("APPLE_MUSIC_STOREFRONT", "us")
        .strip()
        .toLowerCase(java.util.Locale.ROOT);
  }

  public Optional<String> appleMusicUserToken() {
    return optional("APPLE_MUSIC_USER_TOKEN");
  }

  private Optional<String> optional(String key) {
    String raw = env.getOrDefault(key, "").strip();
    return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
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
    return parseInt("IMPORT_MAX_TRACKS", 50);
  }

  public int importSessionTtlS() {
    return parseInt("IMPORT_SESSION_TTL_S", 300);
  }

  public int cooldownUserS() {
    return parseInt("COOLDOWN_USER_S", 15);
  }

  public int queueMax() {
    return parseInt("QUEUE_MAX", 50);
  }

  public int idleDisconnectS() {
    return parseInt("IDLE_DISCONNECT_S", 420);
  }

  public int pausedDisconnectS() {
    return parseInt("PAUSED_DISCONNECT_S", 2700);
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
    return parseDouble("MATCH_THRESHOLD", 0.35);
  }

  /** Simultaneous YouTube + SoundCloud search; false keeps the v1 sequence. */
  public boolean parallelSearch() {
    String raw = env.getOrDefault("PARALLEL_SEARCH", "true").strip();
    if (raw.isEmpty() || raw.equalsIgnoreCase("true")) {
      return true;
    }
    if (raw.equalsIgnoreCase("false")) {
      return false;
    }
    throw new IllegalArgumentException("bad config PARALLEL_SEARCH: must be true or false");
  }

  public String logLevel() {
    return env.getOrDefault("LOG_LEVEL", "INFO");
  }

  private String tokenValue() {
    return env.getOrDefault("DISCORD_TOKEN", "").strip();
  }

  /**
   * Fails the boot on values that would break playback far from the cause.
   * Ranges keep every pinned behavior valid: {@code *_COOLDOWN_S=0} and
   * {@code QUEUE_MAX=0} still boot, only negatives and zeros where a zero
   * hangs are refused.
   */
  private void validate() {
    nonNegative("QUEUE_MAX", queueMax());
    nonNegative("COOLDOWN_USER_S", cooldownUserS());
    nonNegative("PLAY_COOLDOWN_S", playCooldownS());
    nonNegative("SOURCE_DEGRADED_COOLDOWN_S", sourceDegradedCooldownS());
    nonNegative("IDLE_DISCONNECT_S", idleDisconnectS());
    nonNegative("PAUSED_DISCONNECT_S", pausedDisconnectS());
    positive("OCR_TIMEOUT_MS", ocrTimeoutMs());
    positive("RESOLVE_TIMEOUT_MS", resolveTimeoutMs());
    positive("IMG_MAX_BYTES", imgMaxBytes());
    positive("IMG_MAX_PIXELS", imgMaxPixels());
    positive("IMPORT_MAX_TRACKS", importMaxTracks());
    positive("IMPORT_SESSION_TTL_S", importSessionTtlS());
    positive("SOURCE_FAILURE_THRESHOLD", sourceFailureThreshold());
    positive("RESOLVE_CONCURRENCY", resolveConcurrency());
    positive("OCR_MAX_CONCURRENCY", ocrMaxConcurrency());
    double threshold = matchThreshold();
    if (!(threshold >= 0.0) || !(threshold <= 1.0)) {
      throw new IllegalArgumentException("bad config MATCH_THRESHOLD: must be within 0.0..1.0");
    }
    parallelSearch();
    if (spotifyClientId().isPresent() != spotifyClientSecret().isPresent()) {
      throw new IllegalArgumentException(
          "bad config SPOTIFY_CLIENT_ID/SPOTIFY_CLIENT_SECRET: set both or neither");
    }
    if (spotifyRefreshToken().isPresent() && spotifyClientId().isEmpty()) {
      throw new IllegalArgumentException(
          "bad config SPOTIFY_REFRESH_TOKEN: requires SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET");
    }
    if (!appleMusicStorefront().matches("[a-z]{2}")) {
      throw new IllegalArgumentException(
          "bad config APPLE_MUSIC_STOREFRONT: use a two-letter storefront such as us or br");
    }
    if (appleMusicUserToken().isPresent() && appleMusicDeveloperToken().isEmpty()) {
      throw new IllegalArgumentException(
          "bad config APPLE_MUSIC_USER_TOKEN: requires APPLE_MUSIC_DEVELOPER_TOKEN");
    }
  }

  private static void nonNegative(String key, long value) {
    if (value < 0) {
      throw new IllegalArgumentException("bad config " + key + ": must be >= 0");
    }
  }

  private static void positive(String key, long value) {
    if (value <= 0) {
      throw new IllegalArgumentException("bad config " + key + ": must be > 0");
    }
  }

  private static void positive(String key, double value) {
    if (!(value > 0)) {
      throw new IllegalArgumentException("bad config " + key + ": must be > 0");
    }
  }

  private int parseInt(String key, int fallback) {
    String raw = env.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("bad config " + key + ": not a number", e);
    }
  }

  private long parseLong(String key, long fallback) {
    String raw = env.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Long.parseLong(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("bad config " + key + ": not a number", e);
    }
  }

  private double parseDouble(String key, double fallback) {
    String raw = env.get(key);
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Double.parseDouble(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("bad config " + key + ": not a number", e);
    }
  }
}
