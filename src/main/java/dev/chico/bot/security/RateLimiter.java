package dev.chico.bot.security;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Cooldown per key with an injectable clock. Expired keys sweep themselves. */
public final class RateLimiter {

  private final Map<Object, Instant> expiries = new HashMap<>();

  public synchronized boolean tryAcquire(Object key, int cooldownSeconds, Clock clock) {
    Instant now = clock.instant();
    expiries.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
    Instant last = expiries.get(key);
    if (last != null) {
      return false;
    }
    expiries.put(key, now.plusSeconds(cooldownSeconds));
    return true;
  }

  int size() {
    return expiries.size();
  }
}
