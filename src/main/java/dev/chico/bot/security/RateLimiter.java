package dev.chico.bot.security;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Cooldown per key with an injectable clock. */
public final class RateLimiter {

  private final Map<Object, Instant> lastUse = new HashMap<>();

  public synchronized boolean tryAcquire(Object key, int cooldownSeconds, Clock clock) {
    Instant now = clock.instant();
    Instant last = lastUse.get(key);
    if (last != null && now.isBefore(last.plusSeconds(cooldownSeconds))) {
      return false;
    }
    lastUse.put(key, now);
    return true;
  }
}
