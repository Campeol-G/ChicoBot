package dev.chico.bot.source;

import java.net.URI;

/** Outcome of classifying user input as a playable URL or not. */
public sealed interface UrlDecision permits UrlDecision.Accepted, UrlDecision.Rejected {

  /**
   * Accepted link. {@code playlist} marks a list to confirm before enqueueing;
   * {@code singleTrack} keeps the playable track when the input carried both
   * (YouTube {@code watch?v=X&list=Y}).
   */
  record Accepted(Source source, URI uri, boolean playlist, URI singleTrack)
      implements UrlDecision {

    public Accepted(Source source, URI uri) {
      this(source, uri, false, null);
    }
  }

  record Rejected(Reason reason) implements UrlDecision {
  }

  enum Reason {
    INVALID,
    NOT_HTTPS,
    UNKNOWN_HOST,
    SPOTIFY,
    PLAYLIST,
    UNSUPPORTED_LINK
  }
}
