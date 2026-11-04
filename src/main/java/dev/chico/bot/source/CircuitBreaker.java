package dev.chico.bot.source;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Trips a source after consecutive failures, recovers after a cooldown. */
public final class CircuitBreaker {

  private final int failureThreshold;
  private final Duration cooldown;
  private final Clock clock;
  private final Map<Source, State> states = new EnumMap<>(Source.class);

  public CircuitBreaker(int failureThreshold, Duration cooldown, Clock clock) {
    this.failureThreshold = failureThreshold;
    this.cooldown = cooldown;
    this.clock = clock;
  }

  public boolean isOpen(Source source) {
    State state = states.get(source);
    if (state == null || state.failures < failureThreshold) {
      return false;
    }
    if (!clock.instant().isBefore(state.openedAt.plus(cooldown))) {
      states.remove(source);
      return false;
    }
    return true;
  }

  public void recordFailure(Source source) {
    State state = states.computeIfAbsent(source, s -> new State());
    state.failures++;
    if (state.failures >= failureThreshold && state.openedAt == null) {
      state.openedAt = clock.instant();
    }
  }

  public void recordSuccess(Source source) {
    states.remove(source);
  }

  private static final class State {
    int failures;
    Instant openedAt;
  }
}
