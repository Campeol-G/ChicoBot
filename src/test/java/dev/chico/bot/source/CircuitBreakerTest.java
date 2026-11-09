package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

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
  void opensAfterThresholdConsecutiveFailures() {
    CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofMinutes(5), Clock.systemUTC());

    assertFalse(breaker.isOpen(Source.YOUTUBE));
    breaker.recordFailure(Source.YOUTUBE);
    assertFalse(breaker.isOpen(Source.YOUTUBE));
    breaker.recordFailure(Source.YOUTUBE);
    assertFalse(breaker.isOpen(Source.YOUTUBE));
    breaker.recordFailure(Source.YOUTUBE);
    assertTrue(breaker.isOpen(Source.YOUTUBE));
  }

  @Test
  void successResetsFailureCount() {
    CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofMinutes(5), Clock.systemUTC());

    breaker.recordFailure(Source.YOUTUBE);
    breaker.recordFailure(Source.YOUTUBE);
    breaker.recordSuccess(Source.YOUTUBE);
    breaker.recordFailure(Source.YOUTUBE);
    breaker.recordFailure(Source.YOUTUBE);

    assertFalse(breaker.isOpen(Source.YOUTUBE));
  }

  @Test
  void closesAgainAfterCooldown() {
    MutableClock clock = new MutableClock();
    CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMinutes(5), clock);

    breaker.recordFailure(Source.YOUTUBE);
    breaker.recordFailure(Source.YOUTUBE);
    assertTrue(breaker.isOpen(Source.YOUTUBE));

    clock.advance(Duration.ofMinutes(4));
    assertTrue(breaker.isOpen(Source.YOUTUBE));

    clock.advance(Duration.ofMinutes(2));
    assertFalse(breaker.isOpen(Source.YOUTUBE));
  }

  @Test
  void sourcesAreIndependent() {
    CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofMinutes(5), Clock.systemUTC());

    breaker.recordFailure(Source.YOUTUBE);

    assertTrue(breaker.isOpen(Source.YOUTUBE));
    assertFalse(breaker.isOpen(Source.SOUNDCLOUD));
  }

  @Test
  void concurrentFailuresTripExactlyAtThreshold() throws Exception {
    CircuitBreaker breaker = new CircuitBreaker(4000, Duration.ofMinutes(5), Clock.systemUTC());
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<?>> done = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      done.add(
          pool.submit(
              () -> {
                for (int j = 0; j < 500; j++) {
                  breaker.recordFailure(Source.YOUTUBE);
                }
                return null;
              }));
    }
    for (Future<?> f : done) {
      f.get();
    }
    pool.shutdown();
    assertTrue(breaker.isOpen(Source.YOUTUBE));
  }

  @Test
  void isOpenDuringConcurrentFailuresNeverThrows() throws Exception {
    // Best-effort guard for the openedAt null window: readers poll isOpen
    // while a writer trips the breaker; any NPE fails this test.
    CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofMinutes(5), Clock.systemUTC());
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(4);
    java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
    done.add(
        pool.submit(
            () -> {
              for (int j = 0; j < 5000; j++) {
                breaker.recordFailure(Source.YOUTUBE);
              }
              return null;
            }));
    for (int i = 0; i < 3; i++) {
      done.add(
          pool.submit(
              () -> {
                try {
                  for (int j = 0; j < 5000; j++) {
                    breaker.isOpen(Source.YOUTUBE);
                  }
                } catch (RuntimeException e) {
                  failed.set(true);
                }
                return null;
              }));
    }
    for (java.util.concurrent.Future<?> f : done) {
      f.get();
    }
    pool.shutdown();
    assertFalse(failed.get());
    assertTrue(breaker.isOpen(Source.YOUTUBE));
  }
}
