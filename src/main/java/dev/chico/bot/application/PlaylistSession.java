package dev.chico.bot.application;

import java.net.URI;
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

/** One playlist confirmation waiting for the requester to choose. */
public record PlaylistSession(
    UUID id,
    long userId,
    long guildId,
    List<PlaylistItem> items,
    URI singleTrackUri,
    Instant expiresAt) {

  /** In-memory sessions, one active per (user, guild), with TTL cleanup. */
  public static final class Store implements AutoCloseable {
    private final ConcurrentHashMap<UUID, PlaylistSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> active = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;
    private final ScheduledExecutorService cleaner;

    public Store(Duration ttl, Clock clock) {
      this.ttl = ttl;
      this.clock = clock;
      cleaner =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "playlist-session-cleanup");
                thread.setDaemon(true);
                return thread;
              });
      cleaner.scheduleAtFixedRate(this::purgeExpired, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      cleaner.shutdownNow();
    }

    public PlaylistSession create(
        long userId, long guildId, List<PlaylistItem> items, URI singleTrackUri) {
      PlaylistSession session =
          new PlaylistSession(
              UUID.randomUUID(),
              userId,
              guildId,
              List.copyOf(items),
              singleTrackUri,
              clock.instant().plus(ttl));
      UUID previous = active.put(activeKey(userId, guildId), session.id());
      if (previous != null) {
        sessions.remove(previous);
      }
      sessions.put(session.id(), session);
      return session;
    }

    public Optional<PlaylistSession> get(UUID id) {
      PlaylistSession session = sessions.get(id);
      if (session == null) {
        return Optional.empty();
      }
      if (!clock.instant().isBefore(session.expiresAt())) {
        remove(id);
        return Optional.empty();
      }
      return Optional.of(session);
    }

    public void remove(UUID id) {
      PlaylistSession removed = sessions.remove(id);
      if (removed != null) {
        active.remove(activeKey(removed.userId(), removed.guildId()), id);
      }
    }

    void purgeExpired() {
      Instant now = clock.instant();
      sessions.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
    }

    private static String activeKey(long userId, long guildId) {
      return userId + ":" + guildId;
    }
  }
}
