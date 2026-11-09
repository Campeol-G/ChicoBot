package dev.chico.bot.source;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Orders search candidates by text similarity. In v1 the threshold only
 * documents intent: the score orders, it never refuses.
 */
public final class Ranker {

  private static final Set<String> VERSION_WORDS =
      Set.of(
          "live",
          "cover",
          "remix",
          "acoustic",
          "karaoke",
          "instrumental",
          "demo",
          "sped",
          "slowed",
          "nightcore",
          "mashup",
          "reverb",
          "tiktok",
          "lyric",
          "lyrics",
          "letra");

  private static final long SHORT_SECONDS = 30;
  private static final long LONG_SECONDS = 15 * 60;
  private static final double VERSION_PENALTY = 0.8;
  private static final double DURATION_PENALTY = 0.9;

  private final double matchThreshold;

  public Ranker(double matchThreshold) {
    this.matchThreshold = matchThreshold;
  }

  /** Minimum score for an OCR-imported track; text queries are never gated. */
  public double threshold() {
    return matchThreshold;
  }

  public double score(TrackCandidate candidate, String targetText) {
    String candidateText = normalize(candidate.title() + " " + candidate.artist());
    String target = normalize(targetText);
    double score = jaroWinkler(candidateText, target);
    if (hasUnwantedVersion(candidateText, target)) {
      score *= VERSION_PENALTY;
    }
    if (candidate.duration() != null) {
      long seconds = candidate.duration().getSeconds();
      if (seconds < SHORT_SECONDS || seconds > LONG_SECONDS) {
        score *= DURATION_PENALTY;
      }
    }
    return Math.min(1.0, Math.max(0.0, score));
  }

  public Optional<TrackCandidate> best(
      List<TrackCandidate> candidates, String targetText) {
    return candidates.stream().max(Comparator.comparingDouble(c -> score(c, targetText)));
  }

  private static boolean hasUnwantedVersion(String candidateText, String target) {
    Set<String> targetWords = new java.util.HashSet<>(java.util.Arrays.asList(target.split(" ")));
    for (String word : candidateText.split(" ")) {
      if (VERSION_WORDS.contains(word) && !targetWords.contains(word)) {
        return true;
      }
    }
    return false;
  }

  static String normalize(String text) {
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i++) {
      char ch = normalized.charAt(i);
      if (Character.isLetterOrDigit(ch)) {
        out.append(ch);
      } else if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
        out.append(' ');
      }
    }
    return out.toString().trim();
  }

  static double jaroWinkler(String first, String second) {
    if (first.equals(second)) {
      return 1.0;
    }
    double jaro = jaro(first, second);
    int prefix = 0;
    int maxPrefix = Math.min(4, Math.min(first.length(), second.length()));
    while (prefix < maxPrefix && first.charAt(prefix) == second.charAt(prefix)) {
      prefix++;
    }
    return jaro + prefix * 0.1 * (1.0 - jaro);
  }

  private static double jaro(String first, String second) {
    if (first.isEmpty() || second.isEmpty()) {
      return 0.0;
    }
    int matchDistance = Math.max(first.length(), second.length()) / 2 - 1;
    if (matchDistance < 0) {
      matchDistance = 0;
    }
    boolean[] firstMatched = new boolean[first.length()];
    boolean[] secondMatched = new boolean[second.length()];
    int matches = 0;
    for (int i = 0; i < first.length(); i++) {
      int start = Math.max(0, i - matchDistance);
      int end = Math.min(second.length() - 1, i + matchDistance);
      for (int j = start; j <= end; j++) {
        if (!secondMatched[j] && first.charAt(i) == second.charAt(j)) {
          firstMatched[i] = true;
          secondMatched[j] = true;
          matches++;
          break;
        }
      }
    }
    if (matches == 0) {
      return 0.0;
    }
    int transpositions = 0;
    int k = 0;
    for (int i = 0; i < first.length(); i++) {
      if (firstMatched[i]) {
        while (!secondMatched[k]) {
          k++;
        }
        if (first.charAt(i) != second.charAt(k)) {
          transpositions++;
        }
        k++;
      }
    }
    double m = matches;
    return (m / first.length() + m / second.length() + (m - transpositions / 2.0) / m) / 3.0;
  }
}
