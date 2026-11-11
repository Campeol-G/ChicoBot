package dev.chico.bot.application;

import java.util.List;
import java.util.UUID;

/** Fetch result shown before enqueueing a playlist. */
public record PlaylistPreview(
    UUID sessionId, List<PlaylistItem> items, boolean canPlaySingle, String error) {

  public static PlaylistPreview error(String message) {
    return new PlaylistPreview(null, List.of(), false, message);
  }
}
