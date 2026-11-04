package dev.chico.bot.application;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.chico.bot.audio.GuildMusicManager;
import dev.chico.bot.config.Config;
import dev.chico.bot.source.DefaultTrackResolver;
import dev.chico.bot.source.LavaplayerTrackLoader;
import dev.chico.bot.source.MetadataLookup;
import dev.chico.bot.source.PlayQuery;
import dev.chico.bot.source.Ranker;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import dev.chico.bot.source.TrackLoader;
import dev.chico.bot.source.TrackResolver;
import dev.chico.bot.source.TrackUrlPolicy;
import dev.chico.bot.source.UrlDecision;
import dev.chico.bot.source.UrlQuery;
import dev.chico.bot.audio.AudioPlayerSendHandler;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import dev.chico.bot.source.PatchedTvClient;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves any input to playable candidates and enqueues the best one. */
public final class PlaybackService {

  private static final int MAX_SEARCH_RESULTS = 5;
  private static final URI ITUNES_LOOKUP = URI.create("https://itunes.apple.com/lookup");

  private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

  private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
  private final Map<Long, GuildMusicManager> managers = new ConcurrentHashMap<>();
  private final VoiceConnector connector;
  private final TrackResolver resolver;
  private final MetadataLookup metadata;

  public PlaybackService(VoiceConnector connector, Config config) {
    this.connector = connector;
    Duration resolveTimeout = Duration.ofMillis(config.resolveTimeoutMs());
    YoutubeAudioSourceManager youtube = createYoutubeSource(config);
    playerManager.registerSourceManager(youtube);
    playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
    Map<Source, TrackLoader> loaders = new EnumMap<>(Source.class);
    loaders.put(
        Source.YOUTUBE,
        new LavaplayerTrackLoader(Source.YOUTUBE, playerManager, MAX_SEARCH_RESULTS, resolveTimeout));
    loaders.put(
        Source.SOUNDCLOUD,
        new LavaplayerTrackLoader(
            Source.SOUNDCLOUD, playerManager, MAX_SEARCH_RESULTS, resolveTimeout));
    this.resolver =
        new DefaultTrackResolver(
            loaders,
            new Ranker(config.matchThreshold()),
            new dev.chico.bot.source.CircuitBreaker(
                config.sourceFailureThreshold(),
                Duration.ofSeconds(config.sourceDegradedCooldownS()),
                Clock.systemUTC()),
            resolveTimeout);
    this.metadata = new MetadataLookup(ITUNES_LOOKUP, resolveTimeout);
  }

  public AudioPlayerSendHandler sendHandler(long guildId) {
    return musicManager(guildId).sendHandler();
  }

  /** Resolves the input, enqueues the best candidate and reports what happened. */
  public CompletableFuture<String> play(long guildId, long voiceChannelId, String input) {
    UrlDecision decision = TrackUrlPolicy.classify(input);
    if (decision instanceof UrlDecision.Rejected rejected) {
      if (rejected.reason() == UrlDecision.Reason.INVALID) {
        return playQuery(guildId, voiceChannelId, new dev.chico.bot.source.TextQuery(input));
      }
      return CompletableFuture.completedFuture(refusalMessage(rejected.reason()));
    }
    UrlDecision.Accepted accepted = (UrlDecision.Accepted) decision;
    UrlQuery query = new UrlQuery(accepted.source(), accepted.uri());
    if (accepted.source() == Source.YOUTUBE || accepted.source() == Source.SOUNDCLOUD) {
      return playQuery(guildId, voiceChannelId, query);
    }
    return metadata
        .artistTitle(query)
        .thenCompose(
            text ->
                text.map(
                        found ->
                            playQuery(
                                guildId,
                                voiceChannelId,
                                new dev.chico.bot.source.TextQuery(found)))
                    .orElseGet(
                        () ->
                            CompletableFuture.completedFuture(
                                "Could not read that link. Send the track name instead.")));
  }

  private CompletableFuture<String> playQuery(
      long guildId, long voiceChannelId, PlayQuery query) {
    return resolver
        .resolve(query)
        .thenApply(
            candidates -> {
              if (candidates.isEmpty()) {
                return "Nothing found. Try another name or link.";
              }
              TrackCandidate best = candidates.get(0);
              connector.connect(guildId, voiceChannelId);
              AudioTrack track = best.track().makeClone();
              musicManager(guildId).scheduler().queue(track);
              return "Queued " + best.title() + " - " + best.artist() + ".";
            })
        .exceptionally(
            failure -> {
              log.warn("Playback failed", failure);
              return "Search failed. Try again later.";
            });
  }

  private static String refusalMessage(UrlDecision.Reason reason) {
    return switch (reason) {
      case PLAYLIST ->
          "Playlists are not supported yet. Send a link to a single track"
              + " (on YouTube, remove `&list=...`).";
      case SPOTIFY -> "Spotify links are not supported yet.";
      case NOT_HTTPS -> "Links must use HTTPS.";
      case UNKNOWN_HOST -> "That link is not from a supported source.";
      case UNSUPPORTED_LINK -> "That link is not supported.";
      case INVALID -> throw new IllegalStateException("unreachable");
    };
  }

  private static YoutubeAudioSourceManager createYoutubeSource(Config config) {
    YoutubeSourceOptions options = new YoutubeSourceOptions();
    // NOTA: remote cipher until a youtube-source release fixes the local
    // signature cipher for current player scripts (upstream issue #241).
    // Recheck by clearing YT_CIPHER_URL: if login-walled videos play, drop this.
    config.ytCipherUrl().ifPresent(url ->
        options.setRemoteCipher(url, config.ytCipherPassword(), "chicos-bot"));
    Optional<String> refreshToken =
        config.ytRefreshToken().filter(token -> !token.isBlank());
    boolean authenticated = refreshToken.isPresent();
    // Tv is the only OAuth-capable client; other clients keep metadata and search working.
    YoutubeAudioSourceManager youtube =
        authenticated
            ? new YoutubeAudioSourceManager(
                options,
                new Music(), new AndroidVr(), new Web(), new WebEmbedded(), new PatchedTvClient())
            : new YoutubeAudioSourceManager(
                options, YoutubeAudioSourceManager.DEFAULT_CLIENTS);
    if (authenticated) {
      youtube.useOauth2(refreshToken.orElseThrow(), true);
      log.info("YouTube OAuth enabled");
    } else {
      log.info("YouTube OAuth disabled, unauthenticated access");
    }
    return youtube;
  }

  private GuildMusicManager musicManager(long guildId) {
    return managers.computeIfAbsent(guildId, id -> new GuildMusicManager(playerManager));
  }
}
