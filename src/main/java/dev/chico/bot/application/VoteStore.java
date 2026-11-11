package dev.chico.bot.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Command-as-vote ballots for skipping/removing someone else's track.
 * Denominator freezes on the first vote; ballots expire silently after 60 s.
 */
public final class VoteStore {

  public enum Action {
    SKIP,
    REMOVE
  }

  public record Result(boolean passed, int votes, int needed) {
  }

  private static final Duration TTL = Duration.ofSeconds(60);

  private record Key(long guildId, Action action, long itemId) {
  }

  private static final class Ballot {
    private final Set<Long> voters = new java.util.HashSet<>();
    private int denominator;
    private Instant expiresAt;
  }

  private final Clock clock;
  private final Map<Key, Ballot> ballots = new ConcurrentHashMap<>();

  public VoteStore(Clock clock) {
    this.clock = clock;
  }

  /** Records a vote, opening the ballot on the first one. */
  public synchronized Result vote(
      long guildId, Action action, long itemId, long userId, int humanCount) {
    Instant now = clock.instant();
    ballots.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
    Key key = new Key(guildId, action, itemId);
    Ballot ballot = ballots.get(key);
    if (ballot == null) {
      ballot = new Ballot();
      ballot.denominator = Math.max(1, humanCount);
      ballot.expiresAt = now.plus(TTL);
      ballots.put(key, ballot);
    }
    ballot.voters.add(userId);
    int needed = majority(ballot.denominator);
    if (ballot.voters.size() >= needed) {
      retireItem(guildId, itemId);
      return new Result(true, ballot.voters.size(), needed);
    }
    return new Result(false, ballot.voters.size(), needed);
  }

  /** Retires skip/remove ballots for one queue identity. */
  public synchronized void retireItem(long guildId, long itemId) {
    ballots.keySet().removeIf(key -> key.guildId() == guildId && key.itemId() == itemId);
  }

  /** Drops ballots whose queue identity no longer exists. */
  public synchronized void pruneGuild(long guildId, Set<Long> liveItemIds) {
    ballots
        .keySet()
        .removeIf(key -> key.guildId() == guildId && !liveItemIds.contains(key.itemId()));
  }

  public synchronized void clearGuild(long guildId) {
    ballots.keySet().removeIf(key -> key.guildId() == guildId);
  }

  public static int majority(int humans) {
    return humans / 2 + 1;
  }
}
