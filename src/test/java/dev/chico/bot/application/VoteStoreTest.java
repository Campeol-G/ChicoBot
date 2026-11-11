package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VoteStoreTest {

  static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-10T12:00:00Z");

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

  @Test
  void majorityIsFloorHalfPlusOne() {
    assertEquals(2, VoteStore.majority(2));
    assertEquals(2, VoteStore.majority(3));
    assertEquals(3, VoteStore.majority(4));
    assertEquals(3, VoteStore.majority(5));
  }

  @Test
  void firstVotePendingSecondPasses() {
    VoteStore store = new VoteStore(Clock.systemUTC());

    VoteStore.Result first = store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 3);
    assertFalse(first.passed());
    assertEquals(1, first.votes());
    assertEquals(2, first.needed());

    VoteStore.Result second = store.vote(1L, VoteStore.Action.SKIP, 10L, 101L, 3);
    assertTrue(second.passed());
  }

  @Test
  void duplicateVoterDoesNotCountTwice() {
    VoteStore store = new VoteStore(Clock.systemUTC());

    store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 2);
    VoteStore.Result again = store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 2);

    assertFalse(again.passed());
    assertEquals(1, again.votes());
  }

  @Test
  void skipAndRemoveBallotsCoexistUntilQuorum() {
    VoteStore store = new VoteStore(Clock.systemUTC());
    store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 3);
    store.vote(1L, VoteStore.Action.REMOVE, 10L, 100L, 3);

    VoteStore.Result passed =
        store.vote(1L, VoteStore.Action.SKIP, 10L, 101L, 3);
    assertTrue(passed.passed());

    VoteStore.Result reopened =
        store.vote(1L, VoteStore.Action.REMOVE, 10L, 102L, 3);
    assertFalse(reopened.passed());
    assertEquals(1, reopened.votes());
  }

  @Test
  void expiredBallotOpensFresh() {
    MutableClock clock = new MutableClock();
    VoteStore store = new VoteStore(clock);
    store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 3);

    clock.advance(Duration.ofSeconds(61));
    VoteStore.Result fresh = store.vote(1L, VoteStore.Action.SKIP, 10L, 101L, 3);

    assertFalse(fresh.passed());
    assertEquals(1, fresh.votes());
  }

  @Test
  void pruneDropsBallotsForDeadIdentities() {
    VoteStore store = new VoteStore(Clock.systemUTC());
    store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 3);
    store.vote(1L, VoteStore.Action.SKIP, 11L, 100L, 3);

    store.pruneGuild(1L, Set.of(11L));

    VoteStore.Result reopened = store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 3);
    assertFalse(reopened.passed());
    assertEquals(1, reopened.votes());
  }

  @Test
  void ownerRetirementDropsAllActions() {
    VoteStore store = new VoteStore(Clock.systemUTC());
    store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 3);
    store.vote(1L, VoteStore.Action.REMOVE, 10L, 100L, 3);

    store.retireItem(1L, 10L);

    VoteStore.Result reopened = store.vote(1L, VoteStore.Action.SKIP, 10L, 100L, 3);
    assertFalse(reopened.passed());
    assertEquals(1, reopened.votes());
  }
}
