package dev.chico.bot.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TrackListMergerTest {

  private static ParsedTrack track(String title, String artist) {
    return new ParsedTrack(title + " - " + artist, title, artist, 0.9);
  }

  @Test
  void oneTrackOverlapRemovedAtBoundary() {
    List<ParsedTrack> first = List.of(track("A", "a"), track("B", "b"));
    List<ParsedTrack> second = List.of(track("B", "b"), track("C", "c"));

    assertEquals(
        List.of(track("A", "a"), track("B", "b"), track("C", "c")),
        TrackListMerger.merge(List.of(first, second)));
  }

  @Test
  void twoTrackOverlapRemovedAtBoundary() {
    List<ParsedTrack> first = List.of(track("A", "a"), track("B", "b"), track("C", "c"));
    List<ParsedTrack> second = List.of(track("B", "b"), track("C", "c"), track("D", "d"));

    assertEquals(
        List.of(track("A", "a"), track("B", "b"), track("C", "c"), track("D", "d")),
        TrackListMerger.merge(List.of(first, second)));
  }

  @Test
  void legitimateRepeatInTheMiddlePreserved() {
    List<ParsedTrack> first = List.of(track("A", "a"), track("B", "b"), track("A", "a"));
    List<ParsedTrack> second = List.of(track("C", "c"));

    assertEquals(
        List.of(track("A", "a"), track("B", "b"), track("A", "a"), track("C", "c")),
        TrackListMerger.merge(List.of(first, second)));
  }

  @Test
  void noOverlapConcatenatesInOrder() {
    List<ParsedTrack> first = List.of(track("A", "a"));
    List<ParsedTrack> second = List.of(track("B", "b"));

    assertEquals(
        List.of(track("A", "a"), track("B", "b")),
        TrackListMerger.merge(List.of(first, second)));
  }

  @Test
  void partialTitleMatchIsNotOverlap() {
    List<ParsedTrack> first = List.of(track("Love", "x"));
    List<ParsedTrack> second = List.of(track("Love Extended", "x"));

    assertEquals(
        List.of(track("Love", "x"), track("Love Extended", "x")),
        TrackListMerger.merge(List.of(first, second)));
  }

  @Test
  void sameTitleDifferentArtistIsNotOverlap() {
    List<ParsedTrack> first = List.of(track("Love", "x"));
    List<ParsedTrack> second = List.of(track("Love", "y"));

    assertEquals(
        List.of(track("Love", "x"), track("Love", "y")),
        TrackListMerger.merge(List.of(first, second)));
  }
}
