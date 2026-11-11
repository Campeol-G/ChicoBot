package dev.chico.bot.discord;

/**
 * Two-clock disconnect policy: empty channel/idle uses the short clock;
 * paused-with-track uses the long clock. Empty wins over paused, so the bot
 * never holds an empty call for the long window.
 */
final class IdleTracker {

  enum Action {
    NONE,
    DISCONNECT
  }

  private final long idleDisconnectS;
  private final long pausedDisconnectS;
  private Long idleSince;
  private Long pausedSince;

  IdleTracker(long idleDisconnectS, long pausedDisconnectS) {
    this.idleDisconnectS = idleDisconnectS;
    this.pausedDisconnectS = pausedDisconnectS;
  }

  Action tick(
      boolean connected,
      boolean channelEmpty,
      boolean paused,
      boolean idle,
      long nowMs) {
    if (!connected) {
      clear();
      return Action.NONE;
    }
    if (channelEmpty) {
      pausedSince = null;
      return trackIdle(nowMs);
    }
    if (paused) {
      idleSince = null;
      return trackPaused(nowMs);
    }
    if (idle) {
      pausedSince = null;
      return trackIdle(nowMs);
    }
    clear();
    return Action.NONE;
  }

  private Action trackIdle(long nowMs) {
    if (idleSince == null) {
      idleSince = nowMs;
    }
    if (nowMs - idleSince >= idleDisconnectS * 1000L) {
      clear();
      return Action.DISCONNECT;
    }
    return Action.NONE;
  }

  private Action trackPaused(long nowMs) {
    if (pausedSince == null) {
      pausedSince = nowMs;
    }
    if (nowMs - pausedSince >= pausedDisconnectS * 1000L) {
      clear();
      return Action.DISCONNECT;
    }
    return Action.NONE;
  }

  private void clear() {
    idleSince = null;
    pausedSince = null;
  }
}
