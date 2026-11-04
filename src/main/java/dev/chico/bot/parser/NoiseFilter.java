package dev.chico.bot.parser;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Detects interface text, play counts, dates and other non-track lines. */
public final class NoiseFilter {

  private static final Set<String> INTERFACE_WORDS =
      Set.of(
          "adicionar",
          "add",
          "baixado",
          "downloaded",
          "aleatório",
          "aleatorio",
          "shuffle",
          "title",
          "título",
          "titulo",
          "album",
          "álbum",
          "date added",
          "adicionado em");

  private static final Pattern DURATION = Pattern.compile("^\\d{1,3}:\\d{2}$");
  private static final Pattern PURE_NUMBER = Pattern.compile("^\\d+[.)]?$");
  private static final Pattern SYMBOLS_ONLY = Pattern.compile("^[\\W_]+$");
  private static final Pattern PLAY_COUNT =
      Pattern.compile("(?i).*\\b(tocou|vezes|views|plays|streams)\\b.*");
  private static final Pattern SHORT_COUNT =
      Pattern.compile("(?i)^\\d+[\\.,]?\\d*\\s*(mi|mil|k|m)\\b.*");
  private static final Pattern RELATIVE_DATE =
      Pattern.compile("(?i)^h[áa]\\s+\\d+\\s+\\w+.*");
  private static final Pattern FULL_DATE =
      Pattern.compile("^\\d{1,2} de \\S+ de \\d{4}$");
  private static final Pattern FULL_DATE_EN =
      Pattern.compile("^[a-z]{3} \\d{1,2}, \\d{4}$");

  private NoiseFilter() {
  }

  /**
   * True for header-like lines: more than one token where interface words and
   * OCR garbage form a strict majority, with at least one interface hit
   * (headers like "# Título ®" or "Title Album Date added"). Single-token
   * lines never match, so real titles like "Add" or "Title" survive; whole-line
   * exact matches stay in {@link #isNoise}.
   */
  public static boolean containsInterfaceWord(String normalizedText) {
    if (normalizedText == null) {
      return false;
    }
    List<String> tokens =
        Arrays.stream(normalizedText.toLowerCase(Locale.ROOT).split("\\s+"))
            .filter(token -> !token.isEmpty())
            .toList();
    if (tokens.size() < 2) {
      return false;
    }
    int interfaceHits = 0;
    int garbage = 0;
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      if (INTERFACE_WORDS.contains(token)) {
        interfaceHits++;
      } else if (i + 1 < tokens.size()
          && INTERFACE_WORDS.contains(token + " " + tokens.get(i + 1))) {
        interfaceHits++;
      } else if (SYMBOLS_ONLY.matcher(token).matches() || isGarbageToken(token)) {
        garbage++;
      }
    }
    return interfaceHits > 0 && (interfaceHits + garbage) * 2 > tokens.size();
  }

  private static boolean isGarbageToken(String token) {
    if (token.length() > 2) {
      return false;
    }
    for (int i = 0; i < token.length(); i++) {
      if (!Character.isLetterOrDigit(token.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  public static boolean isNoise(String input) {
    if (input == null) {
      return true;
    }
    String text = TextNormalizer.normalize(input).toLowerCase(Locale.ROOT);
    if (text.isEmpty()) {
      return true;
    }
    if (INTERFACE_WORDS.contains(text)) {
      return true;
    }
    return DURATION.matcher(text).matches()
        || PURE_NUMBER.matcher(text).matches()
        || SYMBOLS_ONLY.matcher(text).matches()
        || PLAY_COUNT.matcher(text).matches()
        || SHORT_COUNT.matcher(text).matches()
        || RELATIVE_DATE.matcher(text).matches()
        || FULL_DATE.matcher(text).matches()
        || FULL_DATE_EN.matcher(text).matches();
  }
}
