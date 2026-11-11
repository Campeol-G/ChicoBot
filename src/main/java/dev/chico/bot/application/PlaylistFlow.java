package dev.chico.bot.application;

import dev.chico.bot.source.MetadataLookup;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.SpotifyClient;
import dev.chico.bot.source.TrackCandidate;
import dev.chico.bot.source.TrackResolver;
import dev.chico.bot.source.UrlDecision;
import dev.chico.bot.source.UrlQuery;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Fetches playlist links and stores the confirmation session. */
public final class PlaylistFlow {

  private final TrackResolver resolver;
  private final MetadataLookup metadata;
  private final PlaylistSession.Store sessions;

  public PlaylistFlow(
      TrackResolver resolver,
      MetadataLookup metadata,
      Duration ttl,
      Clock clock) {
    this.resolver = resolver;
    this.metadata = metadata;
    this.sessions = new PlaylistSession.Store(ttl, clock);
  }

  public CompletableFuture<PlaylistPreview> preview(
      long userId, long guildId, UrlDecision.Accepted accepted) {
    if (accepted.source() == Source.SPOTIFY) {
      return metadata
          .spotifyPlaylist(new UrlQuery(Source.SPOTIFY, accepted.uri()))
          .thenApply(PlaylistFlow::spotifyPreview)
          .thenApply(
              preview ->
                  preview.error() != null
                      ? preview
                      : create(userId, guildId, preview.items(), accepted.singleTrack()))
          .exceptionally(failure -> PlaylistPreview.error("Search failed. Try again later."));
    }
    return resolver
        .resolvePlaylist(new UrlQuery(accepted.source(), accepted.uri()))
        .thenApply(
            candidates -> {
              if (candidates.isEmpty()) {
                if (accepted.singleTrack() != null) {
                  return create(userId, guildId, List.of(), accepted.singleTrack());
                }
                return PlaylistPreview.error("That playlist is private or unavailable.");
              }
              return create(
                  userId,
                  guildId,
                  candidates.stream()
                      .map(c -> new PlaylistItem(c.title(), c.artist(), c))
                      .toList(),
                  accepted.singleTrack());
            })
        .exceptionally(failure -> PlaylistPreview.error("Search failed. Try again later."));
  }

  public Optional<PlaylistSession> get(UUID id) {
    return sessions.get(id);
  }

  public void remove(UUID id) {
    sessions.remove(id);
  }

  private static PlaylistPreview spotifyPreview(SpotifyClient.Content content) {
    if (content.unavailable()) {
      return PlaylistPreview.error("That playlist is private or unavailable.");
    }
    if (content.kind() != SpotifyClient.Kind.PLAYLIST) {
      return PlaylistPreview.error(
          "Could not read that link. Send the track name instead.");
    }
    return new PlaylistPreview(
        null,
        content.tracks().stream().map(title -> new PlaylistItem(title, "", null)).toList(),
        false,
        null);
  }

  private PlaylistPreview create(
      long userId, long guildId, List<PlaylistItem> items, java.net.URI singleTrackUri) {
    if (items.isEmpty() && singleTrackUri == null) {
      return PlaylistPreview.error("That playlist is empty.");
    }
    PlaylistSession session = sessions.create(userId, guildId, items, singleTrackUri);
    return new PlaylistPreview(
        session.id(), session.items(), session.singleTrackUri() != null, null);
  }
}
