package dev.chico.bot.application;

import dev.chico.bot.source.TrackCandidate;

/** One row of a playlist confirmation; metadata-only rows resolve on play. */
public record PlaylistItem(String title, String artist, TrackCandidate candidate) {

  public String searchText() {
    return artist == null || artist.isBlank() ? title : artist + " " + title;
  }
}
