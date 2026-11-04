package dev.chico.bot.parser;

import dev.chico.bot.ocr.OcrResult;
import java.util.List;

/** Turns OCR words into track candidates, one strategy at a time. */
public interface TrackLineParser {

  List<ParsedTrack> parse(OcrResult ocr);
}
