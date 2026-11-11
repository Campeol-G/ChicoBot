package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IdleTrackerTest {

  private static final long IDLE_S = 420;
  private static final long PAUSED_S = 2700;

  private static IdleTracker tracker() {
    return new IdleTracker(IDLE_S, PAUSED_S);
  }

  @Test
  void emptyChannelDisconnectsAfterIdleWindow() {
    IdleTracker tracker = tracker();

    assertEquals(IdleTracker.Action.NONE, tracker.tick(true, true, false, true, 0));
    assertEquals(
        IdleTracker.Action.NONE, tracker.tick(true, true, false, true, 419_000));
    assertEquals(
        IdleTracker.Action.DISCONNECT, tracker.tick(true, true, false, true, 420_000));
  }

  @Test
  void pausedTrackUsesLongWindow() {
    IdleTracker tracker = tracker();

    assertEquals(IdleTracker.Action.NONE, tracker.tick(true, false, true, false, 0));
    assertEquals(
        IdleTracker.Action.NONE, tracker.tick(true, false, true, false, 2_699_000));
    assertEquals(
        IdleTracker.Action.DISCONNECT, tracker.tick(true, false, true, false, 2_700_000));
  }

  @Test
  void emptyChannelBeatsPaused() {
    IdleTracker tracker = tracker();

    assertEquals(IdleTracker.Action.NONE, tracker.tick(true, true, true, false, 0));
    assertEquals(
        IdleTracker.Action.NONE, tracker.tick(true, true, true, false, 419_000));
    assertEquals(
        IdleTracker.Action.DISCONNECT, tracker.tick(true, true, true, false, 420_000));
  }

  @Test
  void resumeResetsPausedClock() {
    IdleTracker tracker = tracker();

    tracker.tick(true, false, true, false, 0);
    assertEquals(
        IdleTracker.Action.NONE, tracker.tick(true, false, false, false, 2_000_000));
    assertEquals(
        IdleTracker.Action.NONE, tracker.tick(true, false, true, false, 2_100_000));
    assertEquals(
        IdleTracker.Action.NONE, tracker.tick(true, false, true, false, 4_799_000));
    assertEquals(
        IdleTracker.Action.DISCONNECT, tracker.tick(true, false, true, false, 4_800_000));
  }

  @Test
  void disconnectClearsBothClocks() {
    IdleTracker tracker = tracker();

    tracker.tick(true, false, false, true, 0);
    tracker.tick(false, false, false, false, 300_000);
    assertEquals(
        IdleTracker.Action.NONE, tracker.tick(true, false, false, true, 301_000));
    assertEquals(
        IdleTracker.Action.DISCONNECT, tracker.tick(true, false, false, true, 721_000));
  }
}
