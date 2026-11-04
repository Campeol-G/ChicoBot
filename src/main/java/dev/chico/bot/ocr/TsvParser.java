package dev.chico.bot.ocr;

import java.util.ArrayList;
import java.util.List;

/** Parses Tesseract TSV output into words. Skips headers and non-word rows. */
public final class TsvParser {

  private TsvParser() {
  }

  public static List<OcrWord> parse(String tsv) {
    List<OcrWord> words = new ArrayList<>();
    if (tsv == null || tsv.isEmpty()) {
      return words;
    }
    String[] lines = tsv.split("\n");
    for (int i = 1; i < lines.length; i++) {
      parseLine(lines[i], words);
    }
    return List.copyOf(words);
  }

  private static void parseLine(String line, List<OcrWord> words) {
    String[] columns = line.split("\t", -1);
    if (columns.length < 12 || !"5".equals(columns[0].strip())) {
      return;
    }
    String text = columns[11];
    if (text.isEmpty()) {
      return;
    }
    try {
      int lineNumber = Integer.parseInt(columns[4].strip());
      int left = Integer.parseInt(columns[6].strip());
      int top = Integer.parseInt(columns[7].strip());
      int width = Integer.parseInt(columns[8].strip());
      int height = Integer.parseInt(columns[9].strip());
      float confidence = Float.parseFloat(columns[10].strip());
      if (confidence < 0) {
        return;
      }
      words.add(new OcrWord(text, left, top, width, height, confidence, lineNumber));
    } catch (NumberFormatException notANumber) {
      // Skip malformed rows: OCR output must never crash the import.
    }
  }
}
