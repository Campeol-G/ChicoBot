package dev.chico.bot.source;

import java.net.URI;

/** Outcome of classifying user input as a playable URL or not. */
public sealed interface UrlDecision permits UrlDecision.Accepted, UrlDecision.Rejected {

  record Accepted(Source source, URI uri) implements UrlDecision {
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
