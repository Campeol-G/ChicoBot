package dev.chico.bot.source;

import java.net.URI;

/** Direct URL query. Only built from an Accepted TrackUrlPolicy decision. */
public record UrlQuery(Source source, URI uri) implements PlayQuery {
}
