package dev.chico.bot.ocr;

/** One recognized word with its bounding box, confidence and TSV line number. */
public record OcrWord(
    String text, int left, int top, int width, int height, float confidence, int line) {
}
