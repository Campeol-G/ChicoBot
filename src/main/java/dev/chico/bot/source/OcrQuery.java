package dev.chico.bot.source;

import dev.chico.bot.parser.ParsedTrack;

/** Single track read from a playlist screenshot. */
public record OcrQuery(ParsedTrack track) implements PlayQuery {
}
