package dev.chico.bot.source;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Loads candidates through Lavaplayer, one instance per source. */
public final class LavaplayerTrackLoader implements TrackLoader {

  private final Source source;
  private final AudioPlayerManager manager;
  private final int maxSearchResults;
  private final Duration loadTimeout;

  public LavaplayerTrackLoader(
      Source source, AudioPlayerManager manager, int maxSearchResults, Duration loadTimeout) {
    this.source = source;
    this.manager = manager;
    this.maxSearchResults = maxSearchResults;
    this.loadTimeout = loadTimeout;
  }

  @Override
  public Source source() {
    return source;
  }

  @Override
  public CompletableFuture<List<TrackCandidate>> search(String prefixedQuery) {
    CompletableFuture<List<TrackCandidate>> future = new CompletableFuture<>();
    manager.loadItem(
        prefixedQuery,
        new AudioLoadResultHandler() {
          @Override
          public void trackLoaded(AudioTrack track) {
            future.complete(List.of(candidate(track, null)));
          }

          @Override
          public void playlistLoaded(AudioPlaylist playlist) {
            List<TrackCandidate> candidates = new ArrayList<>();
            List<AudioTrack> tracks = playlist.getTracks();
            for (int i = 0; i < Math.min(tracks.size(), maxSearchResults); i++) {
              candidates.add(candidate(tracks.get(i), null));
            }
            future.complete(List.copyOf(candidates));
          }

          @Override
          public void noMatches() {
            future.complete(List.of());
          }

          @Override
          public void loadFailed(FriendlyException exception) {
            future.completeExceptionally(exception);
          }
        });
    return future.orTimeout(loadTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public CompletableFuture<List<TrackCandidate>> loadUrl(URI uri) {
    CompletableFuture<List<TrackCandidate>> future = new CompletableFuture<>();
    manager.loadItem(
        uri.toString(),
        new AudioLoadResultHandler() {
          @Override
          public void trackLoaded(AudioTrack track) {
            future.complete(List.of(candidate(track, uri)));
          }

          @Override
          public void playlistLoaded(AudioPlaylist playlist) {
            AudioTrack first =
                playlist.getSelectedTrack() != null
                    ? playlist.getSelectedTrack()
                    : playlist.getTracks().get(0);
            future.complete(List.of(candidate(first, uri)));
          }

          @Override
          public void noMatches() {
            future.complete(List.of());
          }

          @Override
          public void loadFailed(FriendlyException exception) {
            future.completeExceptionally(exception);
          }
        });
    return future.orTimeout(loadTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  private TrackCandidate candidate(AudioTrack track, URI fallbackUri) {
    AudioTrackInfo info = track.getInfo();
    URI uri = fallbackUri;
    if (info.uri != null) {
      try {
        uri = URI.create(info.uri);
      } catch (IllegalArgumentException ignored) {
        // Keep the fallback URI.
      }
    }
    return new TrackCandidate(
        source, info.title, info.author, Duration.ofMillis(info.length), uri, 0.0, track);
  }
}
