package dev.chico.bot.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Document-level guard for screenshots that are not playlists (code, shell
 * sessions, articles). Short real playlists always pass; only long enough
 * documents can be refused or warned.
 */
public final class NonMusicGuard {

  public enum Verdict {
    NONE,
    WARN,
    REJECT
  }

  public record Result(Verdict verdict, List<ParsedTrack> kept, int dropped) {
  }

  // Calibration: SEC-02 code print (9/9 code lines) must REJECT; the golden
  // playlist set (0/53 signals) must stay NONE.
  static final double REJECT_RATIO = 0.60;
  static final double WARN_RATIO = 0.25;
  static final int MIN_LINES = 5;

  private static final Pattern SHELL_FLAG = Pattern.compile("\\s--[a-z][a-z0-9-]*");
  private static final Pattern VERSION_TOKEN =
      Pattern.compile("\\b(java|python|node|jdk|jre|maven|npm|docker)\\s+\\d+");
  private static final Pattern SEMVER = Pattern.compile("\\bv?\\d+\\.\\d+(\\.\\d+)?\\b");
  private static final Pattern FILE_EXTENSION =
      Pattern.compile(
          "\\.(java|jar|py|js|ts|json|ya?ml|xml|sh|md|sql|gradle|properties)\\b");
  private static final Pattern PATH = Pattern.compile("(^|\\s)(\\./|~/|/usr/|/etc/|/home/)");

  private static final List<String> CODE_WORDS =
      List.of(
          "sudo ",
          "apt ",
          "apt-get ",
          "pip ",
          "pip3 ",
          "npm ",
          "npx ",
          "docker ",
          "kubectl ",
          "brew ",
          "curl ",
          "wget ",
          "chmod ",
          "chown ",
          "export ",
          "import ",
          "function ",
          "const ",
          "mvn ",
          "gradle ",
          "public class",
          "public static",
          "requirements",
          "installation",
          "usage:",
          "--help");

  private NonMusicGuard() {
  }

  /** Drops suspicious lines on a warned preview; refuses clear non-music. */
  public static Result evaluate(List<ParsedTrack> tracks) {
    if (tracks.size() < MIN_LINES) {
      return new Result(Verdict.NONE, List.copyOf(tracks), 0);
    }
    List<ParsedTrack> kept = new ArrayList<>();
    int suspicious = 0;
    for (ParsedTrack track : tracks) {
      String text = (track.title() + " " + track.artist()).strip();
      if (isCodeLine(text) || isProseLine(text)) {
        suspicious++;
      } else {
        kept.add(track);
      }
    }
    double ratio = suspicious / (double) tracks.size();
    if (ratio >= REJECT_RATIO) {
      return new Result(Verdict.REJECT, List.of(), suspicious);
    }
    if (ratio >= WARN_RATIO) {
      return new Result(Verdict.WARN, List.copyOf(kept), suspicious);
    }
    return new Result(Verdict.NONE, List.copyOf(tracks), 0);
  }

  static boolean isCodeLine(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String lowered = text.toLowerCase(Locale.ROOT).strip();
    if (lowered.startsWith("$ ") || lowered.startsWith("> ") || lowered.startsWith("#!")
        || lowered.startsWith("--")) {
      return true;
    }
    if (lowered.contains("://") || lowered.contains("{") || lowered.contains("}")) {
      return true;
    }
    if (lowered.endsWith(";")) {
      return true;
    }
    if (SHELL_FLAG.matcher(lowered).find()
        || VERSION_TOKEN.matcher(lowered).find()
        || FILE_EXTENSION.matcher(lowered).find()
        || PATH.matcher(lowered).find()) {
      return true;
    }
    if (SEMVER.matcher(lowered).find() && lowered.length() > 12) {
      return true;
    }
    for (String word : CODE_WORDS) {
      if (lowered.startsWith(word) || lowered.contains(" " + word)) {
        return true;
      }
    }
    return false;
  }

  static boolean isProseLine(String text) {
    if (text == null) {
      return false;
    }
    String stripped = text.strip();
    if (stripped.length() < 60) {
      return false;
    }
    long spaces = stripped.chars().filter(ch -> ch == ' ').count();
    if (spaces < 6) {
      return false;
    }
    char last = stripped.charAt(stripped.length() - 1);
    if (last != '.' && last != '?' && last != '!') {
      return false;
    }
    long letters = stripped.chars().filter(Character::isLetter).count();
    long lowercase = stripped.chars().filter(Character::isLowerCase).count();
    return letters > 0 && lowercase * 10 >= letters * 7;
  }
}
