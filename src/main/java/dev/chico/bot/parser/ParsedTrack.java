package dev.chico.bot.parser;

/** One track line read from OCR output, before resolution. */
public record ParsedTrack(String raw, String title, String artist, double confidence) {
}
