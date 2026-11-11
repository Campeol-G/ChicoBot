package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chico.bot.parser.ParsedTrack;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PreviewButtonsTest {

  @Test
  void forgedRemoveValuesAreIgnored() {
    assertEquals(
        List.of(1), PreviewButtons.parseRemoveIndices(List.of("1", "x", "1", "99"), 5));
  }

  @Test
  void reportStringsCarryCountsOnly() {
    assertEquals(
        "Import cancelled — 7 tracks discarded, nothing queued.",
        PreviewButtons.cancelledReport(7));
    assertEquals(
        "Removed 3 items. Import cancelled — nothing queued.",
        PreviewButtons.cancelledWithRemoved(3));
    assertEquals("Removed 2 (5 remaining).", PreviewButtons.removedReport(2, 5));
  }

  @Test
  void removeMenuCapsAt25Options() {
    List<ParsedTrack> tracks =
        java.util.stream.IntStream.range(0, 30)
            .mapToObj(i -> new ParsedTrack("r" + i, "t" + i, "a", 90.0))
            .toList();

    assertDoesNotThrow(() -> PreviewButtons.previewCreate(UUID.randomUUID(), tracks, 30));
  }
}
