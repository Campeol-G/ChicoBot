package dev.chico.bot.security;

import java.util.regex.Pattern;

/** Neutralizes mentions, markdown and URLs in untrusted text. */
public final class TextSanitizer {

  private static final int MAX_LENGTH = 200;
  private static final Pattern URL = Pattern.compile("https?://\\S+");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final Pattern ESCAPED =
      Pattern.compile("([\\\\*_~`|<>#])");

  private TextSanitizer() {
  }

  public static String escape(String input) {
    if (input == null) {
      return "";
    }
    String out = URL.matcher(input).replaceAll("");
    out = out.replace("@", "@\u200B");
    out = ESCAPED.matcher(out).replaceAll("\\\\$1");
    out = SPACES.matcher(out).replaceAll(" ").trim();
    if (out.length() > MAX_LENGTH) {
      out = out.substring(0, out.offsetByCodePoints(0, MAX_LENGTH - 1)) + "…";
    }
    return out;
  }
}
