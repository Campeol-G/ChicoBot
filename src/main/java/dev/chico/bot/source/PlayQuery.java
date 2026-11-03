package dev.chico.bot.source;

/** Any request for tracks: free text, an accepted URL, or one OCR track. */
public sealed interface PlayQuery permits TextQuery, UrlQuery, OcrQuery {
}
