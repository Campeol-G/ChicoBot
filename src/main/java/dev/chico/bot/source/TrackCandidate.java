package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.net.URI;
import java.time.Duration;

/** One playable search result. Carries the loaded track, no second lookup. */
public record TrackCandidate(
    Source source,
    String title,
    String artist,
    Duration duration,
    URI uri,
    double score,
    AudioTrack track,
    long itemId,
    long addedBy) {

  /** Source-layer candidate before the queue assigns identity/ownership. */
  public TrackCandidate(
      Source source,
      String title,
      String artist,
      Duration duration,
      URI uri,
      double score,
      AudioTrack track) {
    this(source, title, artist, duration, uri, score, track, 0L, 0L);
  }

  /** Same audio/data, new queue identity and owner. */
  public TrackCandidate withQueueIdentity(long newItemId, long newAddedBy) {
    return new TrackCandidate(
        source, title, artist, duration, uri, score, track, newItemId, newAddedBy);
  }

  /** Same queue identity/owner, fresh audio handle (loop replay). */
  public TrackCandidate withTrack(AudioTrack replayTrack) {
    return new TrackCandidate(
        source, title, artist, duration, uri, score, replayTrack, itemId, addedBy);
  }
}
