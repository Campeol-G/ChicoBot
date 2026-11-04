package dev.chico.bot.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-11-04T00:00:00Z");

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

    void advance(Duration duration) {
      now = now.plus(duration);
    }
  }

  @Test
  void secondCallInsideCooldownIsRejected() {
    MutableClock clock = new MutableClock();
    RateLimiter limiter = new RateLimiter();

    assertTrue(limiter.tryAcquire("user", 15, clock));
    assertFalse(limiter.tryAcquire("user", 15, clock));
  }

  @Test
  void callAfterCooldownIsAccepted() {
    MutableClock clock = new MutableClock();
    RateLimiter limiter = new RateLimiter();

    assertTrue(limiter.tryAcquire("user", 15, clock));
    clock.advance(Duration.ofSeconds(16));

    assertTrue(limiter.tryAcquire("user", 15, clock));
  }

  @Test
  void keysAreIndependent() {
    MutableClock clock = new MutableClock();
    RateLimiter limiter = new RateLimiter();

    assertTrue(limiter.tryAcquire("user-a", 15, clock));
    assertTrue(limiter.tryAcquire("user-b", 15, clock));
    assertFalse(limiter.tryAcquire("user-a", 15, clock));
  }
}
