package dev.chico.bot.ocr;

import java.time.Duration;
import java.util.List;

/** Tesseract TSV output: one word per row with position and confidence. */
public record OcrResult(List<OcrWord> words, Duration elapsed) {
}
