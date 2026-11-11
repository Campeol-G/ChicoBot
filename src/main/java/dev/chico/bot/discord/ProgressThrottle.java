package dev.chico.bot.discord;

import java.util.function.LongSupplier;

/** Allows at most one progress edit per interval. */
final class ProgressThrottle {

  private final long intervalMs;
  private final LongSupplier clock;
  private long lastMs;

  ProgressThrottle(long intervalMs) {
    this(intervalMs, System::currentTimeMillis);
  }

  ProgressThrottle(long intervalMs, LongSupplier clock) {
    this.intervalMs = intervalMs;
    this.clock = clock;
    this.lastMs = clock.getAsLong();
  }

  boolean allow() {
    long now = clock.getAsLong();
    if (now - lastMs >= intervalMs) {
      lastMs = now;
      return true;
    }
    return false;
  }
}
