package dev.chico.bot.source;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** One loader per source. Implemented once per source in a later task. */
public interface TrackLoader {

  Source source();

  CompletableFuture<List<TrackCandidate>> search(String prefixedQuery);

  CompletableFuture<List<TrackCandidate>> loadUrl(URI uri);
}
