package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chico.bot.parser.ParsedTrack;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ImportSessionTest {

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    void advance(Duration step) {
      now.updateAndGet(instant -> instant.plus(step));
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
      return now.get();
    }
  }

  private static ParsedTrack track(String title) {
    return new ParsedTrack(title, title, "", 90.0);
  }

  @Test
  void expiredSessionsAreGone() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-04T15:00:00Z"));
    ImportSession.Store store = new ImportSession.Store(Duration.ofSeconds(60), clock);
    ImportSession session = store.create(1L, 2L, List.of(track("Alpha")));
    assertTrue(store.get(session.id()).isPresent());
    clock.advance(Duration.ofSeconds(61));
    assertTrue(store.get(session.id()).isEmpty());
  }

  @Test
  void customTtlIsHonored() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-04T15:00:00Z"));
    ImportSession.Store store = new ImportSession.Store(Duration.ofSeconds(300), clock);
    ImportSession session = store.create(1L, 2L, List.of(track("Alpha")));
    clock.advance(Duration.ofSeconds(299));
    assertTrue(store.get(session.id()).isPresent());
    clock.advance(Duration.ofSeconds(2));
    assertTrue(store.get(session.id()).isEmpty());
  }

  @Test
  void updateTracksKeepsSessionAlive() {
    MutableClock clock = new MutableClock(Instant.parse("2026-11-04T15:00:00Z"));
    ImportSession.Store store = new ImportSession.Store(Duration.ofSeconds(300), clock);
    ImportSession session = store.create(1L, 2L, List.of(track("Alpha"), track("Beta")));
    var updated = store.updateTracks(session.id(), List.of(track("Alpha")));
    assertTrue(updated.isPresent());
    assertEquals(List.of("Alpha"), updated.orElseThrow().tracks().stream().map(ParsedTrack::title).toList());
  }
}
