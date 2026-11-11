package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProgressThrottleTest {

  @Test
  void allowsOneEditPerInterval() {
    AtomicLong now = new AtomicLong(1_000);
    ProgressThrottle throttle = new ProgressThrottle(2_000, now::get);

    assertFalse(throttle.allow());
    now.addAndGet(1_999);
    assertFalse(throttle.allow());
    now.addAndGet(1);
    assertTrue(throttle.allow());
    assertFalse(throttle.allow());
  }
}
