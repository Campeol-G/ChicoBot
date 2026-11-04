package dev.chico.bot.parser;

import dev.chico.bot.ocr.OcrResult;
import dev.chico.bot.ocr.OcrWord;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Groups OCR words into lines and rows, then reads title and artist per row.
 * Title-only rows (prints without artist) yield an empty artist.
 */
public final class DefaultTrackLineParser implements TrackLineParser {

  private static final double MIN_CONFIDENCE = 30.0;
  private static final double CONTENT_COLUMN_FRACTION = 0.35;
  private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d+[.)]?$");
  private static final Pattern PURE_NUMBER = Pattern.compile("^\\d+$");
  private static final Pattern SINGLE_LOWER_LETTER = Pattern.compile("^[a-z]$");
  private static final Pattern SINGLE_LETTER = Pattern.compile("^[a-zA-Z]$");
  private static final Pattern TRAILING_DURATION = Pattern.compile("^\\d{1,3}:\\d{2}$");
  private static final Pattern BADGE_TOKEN = Pattern.compile("^(E|B|El)$");
  private static final Pattern SYMBOL_TOKEN = Pattern.compile("^[\\W_]+$");
  private static final Pattern ARTIST_ALBUM_SPLIT = Pattern.compile("\\s*[•·|]\\s*");

  @Override
  public List<ParsedTrack> parse(OcrResult ocr) {
    if (ocr.words().isEmpty()) {
      return List.of();
    }
    int pageWidth = ocr.words().stream().mapToInt(w -> w.left() + w.width()).max().orElse(0);
    List<ContentLine> content = new ArrayList<>();
    for (Line line : groupLines(ocr.words())) {
      for (List<OcrWord> cell : splitColumns(line)) {
        List<OcrWord> kept = clean(cell, pageWidth);
        if (kept.isEmpty()) {
          continue;
        }
        if (pageWidth > 0 && kept.get(0).left() >= pageWidth * CONTENT_COLUMN_FRACTION) {
          continue;
        }
        String text = join(kept);
        if (NoiseFilter.isNoise(text) || averageConfidence(kept) < MIN_CONFIDENCE) {
          continue;
        }
        content.add(new ContentLine(kept, text));
      }
    }
    List<ParsedTrack> tracks = new ArrayList<>();
    for (List<ContentLine> row : groupRows(content)) {
      trackOf(row).ifPresent(tracks::add);
    }
    return List.copyOf(tracks);
  }

  private static double averageConfidence(List<OcrWord> words) {
    return words.stream().mapToDouble(OcrWord::confidence).average().orElse(0);
  }

  private static java.util.Optional<ParsedTrack> trackOf(List<ContentLine> row) {
    List<String> parts = new ArrayList<>();
    for (ContentLine line : row) {
      parts.add(TextNormalizer.normalize(line.text()));
    }
    if (parts.isEmpty()) {
      return java.util.Optional.empty();
    }
    String title = parts.get(0);
    String artist = "";
    if (parts.size() > 1) {
      artist = splitArtist(parts.get(1));
    }
    if (title.isEmpty()) {
      return java.util.Optional.empty();
    }
    String raw = title + (artist.isEmpty() ? "" : " - " + artist);
    double confidence = row.stream().mapToDouble(ContentLine::confidence).average().orElse(0);
    return java.util.Optional.of(new ParsedTrack(raw, title, artist, confidence));
  }

  private record ContentLine(List<OcrWord> words, String text) {
    double confidence() {
      return words.stream().mapToDouble(OcrWord::confidence).average().orElse(0);
    }

    int top() {
      return words.stream().mapToInt(OcrWord::top).min().orElse(0);
    }

    int bottom() {
      return words.stream().mapToInt(w -> w.top() + w.height()).max().orElse(0);
    }
  }

  private static String splitArtist(String line) {
    String[] parts = ARTIST_ALBUM_SPLIT.split(line, 2);
    return TextNormalizer.normalize(parts[0]);
  }

  private static List<List<OcrWord>> splitColumns(Line line) {
    List<OcrWord> words = line.words();
    double medianHeight =
        median(words.stream().mapToDouble(OcrWord::height).sorted().toArray());
    double columnGap = Math.max(20.0, medianHeight * 2.5);
    List<List<OcrWord>> cells = new ArrayList<>();
    List<OcrWord> current = new ArrayList<>();
    int right = Integer.MIN_VALUE;
    for (OcrWord word : words) {
      if (!current.isEmpty() && word.left() - right > columnGap) {
        cells.add(List.copyOf(current));
        current = new ArrayList<>();
      }
      current.add(word);
      right = Math.max(right, word.left() + word.width());
    }
    if (!current.isEmpty()) {
      cells.add(List.copyOf(current));
    }
    return cells;
  }

  private static String join(List<OcrWord> words) {
    StringBuilder text = new StringBuilder();
    for (OcrWord word : words) {
      if (!text.isEmpty()) {
        text.append(' ');
      }
      text.append(word.text());
    }
    return text.toString();
  }

  private static List<OcrWord> clean(List<OcrWord> input, int pageWidth) {
    Deque<OcrWord> words = new ArrayDeque<>(input);
    if (words.size() == 1 && SINGLE_LETTER.matcher(words.getFirst().text()).matches()) {
      return List.of();
    }
    while (!words.isEmpty() && LEADING_NUMBER.matcher(words.getFirst().text()).matches()) {
      words.removeFirst();
    }
    if (words.size() > 1 && SINGLE_LOWER_LETTER.matcher(words.getFirst().text()).matches()) {
      words.removeFirst();
    }
    while (!words.isEmpty()
        && TRAILING_DURATION.matcher(words.getLast().text()).matches()) {
      words.removeLast();
    }
    words = stripBadge(words);
    while (words.size() > 1 && SYMBOL_TOKEN.matcher(words.getFirst().text()).matches()) {
      words.removeFirst();
    }
    while (words.size() > 1 && SYMBOL_TOKEN.matcher(words.getLast().text()).matches()) {
      words.removeLast();
    }
    while (words.size() > 1
        && PURE_NUMBER.matcher(words.getLast().text()).matches()
        && isRightZone(words.getLast(), pageWidth)) {
      words.removeLast();
    }
    return List.copyOf(words);
  }

  private static boolean isRightZone(OcrWord word, int pageWidth) {
    return pageWidth > 0 && word.left() >= pageWidth * 0.5;
  }

  private record OcrWordKey(int block, int paragraph, int line) {
  }

  private static Deque<OcrWord> stripBadge(Deque<OcrWord> words) {
    if (words.size() < 2 || !BADGE_TOKEN.matcher(words.getFirst().text()).matches()) {
      return words;
    }
    List<OcrWord> list = new ArrayList<>(words);
    List<Integer> otherGaps = new ArrayList<>();
    for (int i = 2; i < list.size(); i++) {
      otherGaps.add(list.get(i).left() - (list.get(i - 1).left() + list.get(i - 1).width()));
    }
    double referenceGap =
        otherGaps.isEmpty()
            ? list.get(1).left() - (list.get(0).left() + list.get(0).width())
            : median(otherGaps.stream().mapToDouble(Integer::doubleValue).sorted().toArray());
    int firstGap = list.get(1).left() - (list.get(0).left() + list.get(0).width());
    boolean spacedLikeABadge = referenceGap > 0 && firstGap > 1.8 * referenceGap;
    boolean smallerThanText =
        list.get(0).height() < 0.8 * list.get(1).height() && list.get(0).height() > 0;
    if (spacedLikeABadge || smallerThanText) {
      list.remove(0);
      return new ArrayDeque<>(list);
    }
    return words;
  }

  private static List<Line> groupLines(List<OcrWord> words) {
    Map<OcrWordKey, List<OcrWord>> grouped = new LinkedHashMap<>();
    for (OcrWord word : words) {
      grouped
          .computeIfAbsent(
              new OcrWordKey(word.block(), word.paragraph(), word.line()),
              key -> new ArrayList<>())
          .add(word);
    }
    return new ArrayList<>(
        grouped.values().stream()
            .map(Line::of)
            .sorted(Comparator.comparingInt(Line::top).thenComparingInt(Line::left))
            .toList());
  }

  private static List<List<ContentLine>> groupRows(List<ContentLine> lines) {
    if (lines.size() < 2) {
      return List.of(List.copyOf(lines));
    }
    List<Double> gaps = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      double gap = lines.get(i).top() - lines.get(i - 1).bottom();
      if (gap > 0) {
        gaps.add(gap);
      }
    }
    double threshold = rowThreshold(gaps, lines);
    List<List<ContentLine>> rows = new ArrayList<>();
    List<ContentLine> current = new ArrayList<>();
    current.add(lines.get(0));
    int bottom = lines.get(0).bottom();
    for (int i = 1; i < lines.size(); i++) {
      ContentLine line = lines.get(i);
      if (line.top() - bottom > threshold) {
        rows.add(List.copyOf(current));
        current = new ArrayList<>();
      }
      current.add(line);
      bottom = Math.max(bottom, line.bottom());
    }
    rows.add(List.copyOf(current));
    return rows;
  }

  /**
   * Splits within-track gaps from between-track gaps at the largest absolute
   * jump between consecutive sorted gaps. Falls back to the median line height
   * when gaps look uniform.
   */
  private static double rowThreshold(List<Double> gaps, List<ContentLine> lines) {
    double medianHeight =
        median(lines.stream().mapToDouble(l -> l.bottom() - l.top()).sorted().toArray());
    if (gaps.size() < 2) {
      return medianHeight;
    }
    List<Double> sorted = new ArrayList<>(gaps);
    sorted.sort(Double::compareTo);
    double floor = Math.max(4.0, medianHeight * 0.5);
    double bestJump = 0;
    int bestIndex = -1;
    for (int i = 0; i + 1 < sorted.size(); i++) {
      double jump = sorted.get(i + 1) - sorted.get(i);
      if (jump >= floor && jump > bestJump) {
        bestJump = jump;
        bestIndex = i;
      }
    }
    if (bestIndex < 0) {
      return medianHeight;
    }
    return (sorted.get(bestIndex) + sorted.get(bestIndex + 1)) / 2.0;
  }

  private static double median(double[] sorted) {
    if (sorted.length == 0) {
      return 0;
    }
    int middle = sorted.length / 2;
    if (sorted.length % 2 == 1) {
      return sorted[middle];
    }
    return (sorted[middle - 1] + sorted[middle]) / 2.0;
  }

  private record Line(List<OcrWord> words, String text, int left, int top, int bottom, int height) {
    static Line of(List<OcrWord> words) {
      List<OcrWord> sorted =
          words.stream().sorted(Comparator.comparingInt(OcrWord::left)).toList();
      StringBuilder text = new StringBuilder();
      int left = Integer.MAX_VALUE;
      int top = Integer.MAX_VALUE;
      int bottom = Integer.MIN_VALUE;
      for (OcrWord word : sorted) {
        if (!text.isEmpty()) {
          text.append(' ');
        }
        text.append(word.text());
        left = Math.min(left, word.left());
        top = Math.min(top, word.top());
        bottom = Math.max(bottom, word.top() + word.height());
      }
      int height = Math.max(1, bottom - top);
      return new Line(List.copyOf(sorted), text.toString(), left, top, bottom, height);
    }

    double averageConfidence() {
      return words.stream().mapToDouble(OcrWord::confidence).average().orElse(0);
    }
  }
}
