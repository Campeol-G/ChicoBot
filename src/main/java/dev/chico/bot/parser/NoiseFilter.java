package dev.chico.bot.parser;

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

  private NoiseFilter() {
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
        || FULL_DATE.matcher(text).matches();
  }
}
