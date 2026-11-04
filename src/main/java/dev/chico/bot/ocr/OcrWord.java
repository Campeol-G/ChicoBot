package dev.chico.bot.ocr;

/**
 * One recognized word with its bounding box, confidence and TSV position.
 * Line numbers restart in every paragraph, so grouping needs block and paragraph too.
 */
public record OcrWord(
    String text,
    int left,
    int top,
    int width,
    int height,
    float confidence,
    int block,
    int paragraph,
    int line) {
}
