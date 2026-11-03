package dev.chico.bot.source;

/** Free-text search query. */
public record TextQuery(String text) implements PlayQuery {
}
