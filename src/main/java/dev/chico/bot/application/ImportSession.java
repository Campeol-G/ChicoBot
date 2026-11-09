package dev.chico.bot.application;

import dev.chico.bot.parser.ParsedTrack;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One confirmed import preview waiting for the owner to act. */
public record ImportSession(
    UUID id, long userId, long guildId, List<ParsedTrack> tracks, Instant expiresAt) {

  /** In-memory sessions with TTL and periodic cleanup. */
  public static final class Store implements AutoCloseable {
    private final ConcurrentHashMap<UUID, ImportSession> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;
    private final ScheduledExecutorService cleaner;

    public Store() {
      this(Duration.ofSeconds(300), Clock.systemUTC());
    }

    public Store(Duration ttl, Clock clock) {
      this.ttl = ttl;
      this.clock = clock;
      cleaner =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "import-session-cleanup");
                thread.setDaemon(true);
                return thread;
              });
      cleaner.scheduleAtFixedRate(this::purgeExpired, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      cleaner.shutdownNow();
    }

    public ImportSession create(long userId, long guildId, List<ParsedTrack> tracks) {
      ImportSession session =
          new ImportSession(
              UUID.randomUUID(), userId, guildId, List.copyOf(tracks),
              clock.instant().plus(ttl));
      sessions.put(session.id(), session);
      return session;
    }

    public Optional<ImportSession> get(UUID id) {
      ImportSession session = sessions.get(id);
      if (session == null) {
        return Optional.empty();
      }
      if (!clock.instant().isBefore(session.expiresAt())) {
        sessions.remove(id);
        return Optional.empty();
      }
      return Optional.of(session);
    }

    public Optional<ImportSession> updateTracks(UUID id, List<ParsedTrack> tracks) {
      Instant now = clock.instant();
      sessions.computeIfPresent(
          id,
          (key, old) -> {
            if (!now.isBefore(old.expiresAt())) {
              return null;
            }
            return new ImportSession(
                old.id(), old.userId(), old.guildId(), List.copyOf(tracks), old.expiresAt());
          });
      return get(id);
    }

    public void remove(UUID id) {
      sessions.remove(id);
    }

    void purgeExpired() {
      Instant now = clock.instant();
      sessions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }
  }
}
