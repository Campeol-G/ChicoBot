package dev.chico.bot.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges per-image track lists in attachment order, removing up to 2 boundary
 * duplicates where consecutive prints overlap. No 25-track cap here.
 */
public final class TrackListMerger {

  private static final int MAX_OVERLAP = 2;

  private TrackListMerger() {
  }

  public static List<ParsedTrack> merge(List<List<ParsedTrack>> perImage) {
    List<ParsedTrack> merged = new ArrayList<>();
    for (List<ParsedTrack> image : perImage) {
      int overlap = overlap(merged, image);
      merged.addAll(image.subList(overlap, image.size()));
    }
    return List.copyOf(merged);
  }

  private static int overlap(List<ParsedTrack> merged, List<ParsedTrack> next) {
    int limit = Math.min(MAX_OVERLAP, Math.min(merged.size(), next.size()));
    for (int size = limit; size > 0; size--) {
      if (matches(
          merged.subList(merged.size() - size, merged.size()), next.subList(0, size))) {
        return size;
      }
    }
    return 0;
  }

  private static boolean matches(List<ParsedTrack> tail, List<ParsedTrack> head) {
    for (int i = 0; i < tail.size(); i++) {
      if (!sameTrack(tail.get(i), head.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameTrack(ParsedTrack a, ParsedTrack b) {
    return key(a).equals(key(b));
  }

  private static String key(ParsedTrack track) {
    return TextNormalizer.normalize(track.title())
        + "\0"
        + TextNormalizer.normalize(track.artist());
  }
}
