package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaylistSessionStoreTest {

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration step) {
      now = now.plus(step);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static PlaylistItem item(String title) {
    return new PlaylistItem(title, "artist", null);
  }

  @Test
  void newLinkReplacesPreviousSessionForSameUserAndGuild() {
    PlaylistSession.Store store =
        new PlaylistSession.Store(Duration.ofMinutes(5), Clock.systemUTC());

    PlaylistSession first = store.create(1L, 2L, List.of(item("A")), null);
    PlaylistSession second = store.create(1L, 2L, List.of(item("B")), null);

    assertTrue(store.get(first.id()).isEmpty());
    assertEquals(second.id(), store.get(second.id()).orElseThrow().id());
    store.close();
  }

  @Test
  void expiredSessionIsGone() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
    PlaylistSession.Store store = new PlaylistSession.Store(Duration.ofMinutes(5), clock);
    PlaylistSession session = store.create(1L, 2L, List.of(item("A")), null);

    clock.advance(Duration.ofMinutes(6));

    assertTrue(store.get(session.id()).isEmpty());
    store.close();
  }

  @Test
  void removingOneSessionKeepsAnotherUsers() {
    PlaylistSession.Store store =
        new PlaylistSession.Store(Duration.ofMinutes(5), Clock.systemUTC());
    PlaylistSession mine = store.create(1L, 2L, List.of(item("A")), null);
    PlaylistSession other = store.create(9L, 2L, List.of(item("B")), null);

    store.remove(mine.id());

    assertTrue(store.get(mine.id()).isEmpty());
    assertTrue(store.get(other.id()).isPresent());
    store.close();
  }
}
