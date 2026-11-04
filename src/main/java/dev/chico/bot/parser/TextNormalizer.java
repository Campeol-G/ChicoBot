package dev.chico.bot.parser;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Unicode cleanup, truncation and URL removal for OCR lines. */
public final class TextNormalizer {

  private static final int MAX_LENGTH = 100;
  private static final Pattern CONTROL =
      Pattern.compile("[\\p{Cc}\\u200B-\\u200F\\uFEFF\\u202A-\\u202E\\u2066-\\u2069\\u061C]");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final Pattern TRAILING_DOTS = Pattern.compile("(\\.{2,}|\\u2026)\\s*$");
  private static final Pattern URL_TOKEN = Pattern.compile("^(https?://\\S+|www\\.\\S+)$");

  private TextNormalizer() {
  }

  public static String normalize(String input) {
    if (input == null) {
      return "";
    }
    String out = Normalizer.normalize(input, Normalizer.Form.NFKC);
    out = SPACES.matcher(out).replaceAll(" ").trim();
    out = CONTROL.matcher(out).replaceAll("");
    StringBuilder kept = new StringBuilder();
    for (String token : out.split(" ")) {
      if (URL_TOKEN.matcher(token).matches()) {
        continue;
      }
      if (!kept.isEmpty()) {
        kept.append(' ');
      }
      kept.append(token);
    }
    out = TRAILING_DOTS.matcher(kept.toString().trim()).replaceAll("").trim();
    if (out.length() > MAX_LENGTH) {
      out = out.substring(0, MAX_LENGTH);
    }
    return out;
  }
}
