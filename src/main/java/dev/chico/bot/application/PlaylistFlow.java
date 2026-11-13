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

  public PlaylistFlow(TrackResolver resolver, Duration ttl, Clock clock) {
    this(resolver, null, ttl, clock);
  }

  public PlaylistFlow(
      TrackResolver resolver, MetadataLookup metadata, Duration ttl, Clock clock) {
    this.resolver = resolver;
    this.metadata = metadata;
    this.sessions = new PlaylistSession.Store(ttl, clock);
  }

  public CompletableFuture<PlaylistPreview> preview(
      long userId, long guildId, UrlDecision.Accepted accepted) {
    if (accepted.source() == Source.SPOTIFY) {
      if (metadata == null || !metadata.spotifyEnabled()) {
        return CompletableFuture.completedFuture(
            PlaylistPreview.error(
                "Spotify playlists need SPOTIFY_CLIENT_ID/SPOTIFY_CLIENT_SECRET; see README."));
      }
      return metadata
          .spotifyPlaylist(new UrlQuery(Source.SPOTIFY, accepted.uri()))
          .thenApply(
              content -> fromContent(userId, guildId, content, accepted.singleTrack()))
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
        .exceptionally(
            failure -> playlistFailure(failure));
  }

  private static PlaylistPreview playlistFailure(Throwable failure) {
    Throwable cause =
        failure instanceof java.util.concurrent.CompletionException
            ? failure.getCause()
            : failure;
    if (cause instanceof com.sedmelluq.discord.lavaplayer.tools.FriendlyException
        && isPrivateMessage(cause.getMessage())) {
      return PlaylistPreview.error("That playlist is private or unavailable.");
    }
    return PlaylistPreview.error("Search failed. Try again later.");
  }

  private static boolean isPrivateMessage(String message) {
    if (message == null) {
      return false;
    }
    String lowered = message.toLowerCase(java.util.Locale.ROOT);
    return lowered.contains("private")
        || lowered.contains("unavailable")
        || lowered.contains("not available");
  }

  public Optional<PlaylistSession> get(UUID id) {
    return sessions.get(id);
  }

  public void remove(UUID id) {
    sessions.remove(id);
  }

  /** Builds the preview/session from already-fetched Spotify content. */
  public PlaylistPreview fromContent(
      long userId,
      long guildId,
      SpotifyClient.Content content,
      java.net.URI singleTrackUri) {
    if (content.transientError()) {
      return PlaylistPreview.error("Search failed. Try again later.");
    }
    if (content.unavailable()) {
      return PlaylistPreview.error("That playlist is private or unavailable.");
    }
    if (content.kind() != SpotifyClient.Kind.PLAYLIST) {
      return PlaylistPreview.error("Could not read that link. Send the track name instead.");
    }
    return create(
        userId,
        guildId,
        content.tracks().stream()
            .map(title -> new PlaylistItem(title, "", null))
            .toList(),
        singleTrackUri);
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
