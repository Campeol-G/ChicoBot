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
    AudioTrack track) {
}
