package dev.chico.bot.source;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Single entry point turning any query into playable candidates. */
public interface TrackResolver {

  CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query);
}
