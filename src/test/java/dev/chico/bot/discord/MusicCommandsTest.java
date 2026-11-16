package dev.chico.bot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MusicCommandsTest {

  @Test
  void refusalMessagesUseTheErrorPath() {
    assertTrue(MusicCommands.isRefusal("Nothing is playing."));
    assertTrue(MusicCommands.isRefusal("No queued track matches that."));
    assertTrue(
        MusicCommands.isRefusal("Cannot remove the current track. Use /skip to skip it."));
    assertTrue(MusicCommands.isRefusal("The queue is full."));
    assertTrue(MusicCommands.isRefusal("Use `/loop` or `/loop all`."));

    assertFalse(MusicCommands.isRefusal("Paused."));
    assertFalse(MusicCommands.isRefusal("Skipped to Beta."));
    assertFalse(MusicCommands.isRefusal("Loop disabled."));
    assertFalse(MusicCommands.isRefusal("Removed Beta (position 2)."));
  }

  @Test
  void clampQueryKeepsMaxLengthQuery() {
    String query = "a".repeat(MusicCommands.MAX_QUERY_LENGTH);

    assertEquals(query, MusicCommands.clampQuery(query));
  }

  @Test
  void clampQueryTruncatesLongQuery() {
    String query = "a".repeat(MusicCommands.MAX_QUERY_LENGTH + 100);

    assertEquals(
        "a".repeat(MusicCommands.MAX_QUERY_LENGTH), MusicCommands.clampQuery(query));
  }

  @Test
  void blocksPlayNeedsHumansAloneElsewhere() {
    assertFalse(MusicCommands.blocksPlay(false, false, 1));
    assertFalse(MusicCommands.blocksPlay(false, false, 0));
    assertFalse(MusicCommands.blocksPlay(true, true, 1));
    assertFalse(MusicCommands.blocksPlay(true, false, 0));
    assertTrue(MusicCommands.blocksPlay(true, false, 1));
  }
}
