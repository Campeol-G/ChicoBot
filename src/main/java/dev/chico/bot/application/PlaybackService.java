package dev.chico.bot.application;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import dev.chico.bot.audio.AudioPlayerSendHandler;
import dev.chico.bot.audio.GuildMusicManager;
import dev.chico.bot.config.Config;
import dev.chico.bot.security.RateLimiter;
import dev.chico.bot.source.CircuitBreaker;
import dev.chico.bot.source.DefaultTrackResolver;
import dev.chico.bot.source.LavaplayerTrackLoader;
import dev.chico.bot.source.MetadataLookup;
import dev.chico.bot.source.PatchedTvClient;
import dev.chico.bot.source.PlayQuery;
import dev.chico.bot.source.Ranker;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TextQuery;
import dev.chico.bot.source.TrackCandidate;
import dev.chico.bot.source.TrackLoader;
import dev.chico.bot.source.TrackResolver;
import dev.chico.bot.source.TrackUrlPolicy;
import dev.chico.bot.source.UrlDecision;
import dev.chico.bot.source.UrlQuery;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
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

/** Resolves any input to playable candidates and controls per-guild playback. */
public final class PlaybackService {

  private static final int MAX_SEARCH_RESULTS = 5;
  private static final URI ITUNES_LOOKUP = URI.create("https://itunes.apple.com/lookup");

  private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);

  /** Outcome of a play request: the queued track or a message for the user. */
  public record PlayResult(boolean queued, String message, TrackCandidate candidate) {
  }

  /** Current track plus the waiting queue, both without audio handles. */
  public record QueueView(Optional<TrackCandidate> current, List<TrackCandidate> queued) {
  }

  private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
  private final Map<Long, GuildMusicManager> managers = new ConcurrentHashMap<>();
  private final VoiceConnector connector;
  private final TrackResolver resolver;
  private final MetadataLookup metadata;
  private final RateLimiter rateLimiter = new RateLimiter();
  private final Clock clock;
  private final int queueMax;
  private final int playCooldownS;

  public PlaybackService(VoiceConnector connector, Config config) {
    this(connector, config, Clock.systemUTC());
  }

  PlaybackService(VoiceConnector connector, Config config, Clock clock) {
    this.connector = connector;
    this.clock = clock;
    this.queueMax = config.queueMax();
    this.playCooldownS = config.playCooldownS();
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
            new CircuitBreaker(
                config.sourceFailureThreshold(),
                Duration.ofSeconds(config.sourceDegradedCooldownS()),
                clock),
            resolveTimeout);
    this.metadata = new MetadataLookup(ITUNES_LOOKUP, resolveTimeout);
  }

  public AudioPlayerSendHandler sendHandler(long guildId) {
    return musicManager(guildId).sendHandler();
  }

  /** Resolves one query to ordered candidates. Used by the import preview. */
  public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
    return resolver.resolve(query);
  }

  /** Connects to a voice channel. Used by the import preview. */
  public void connect(long guildId, long voiceChannelId) {
    connector.connect(guildId, voiceChannelId);
  }

  /**
   * Enqueues already-resolved candidates in order with one call, cloning each
   * playable track. Returns how many were accepted before the queue filled.
   */
  public int enqueueResolved(long guildId, List<TrackCandidate> candidates) {
    return musicManager(guildId)
        .enqueueAll(candidates.stream().map(PlaybackService::cloneOf).toList());
  }

  /** Resolves the input, enqueues the best candidate and reports what happened. */
  public CompletableFuture<PlayResult> play(
      long guildId, long voiceChannelId, long userId, String input) {
    if (!rateLimiter.tryAcquire(userId, playCooldownS, clock)) {
      return CompletableFuture.completedFuture(
          refused("Slow down. Try again in a few seconds."));
    }
    UrlDecision decision = TrackUrlPolicy.classify(input);
    if (decision instanceof UrlDecision.Rejected rejected) {
      if (rejected.reason() == UrlDecision.Reason.INVALID) {
        return playQuery(guildId, voiceChannelId, new TextQuery(input));
      }
      return CompletableFuture.completedFuture(refused(refusalMessage(rejected.reason())));
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
                        found -> playQuery(guildId, voiceChannelId, new TextQuery(found)))
                    .orElseGet(
                        () ->
                            CompletableFuture.completedFuture(
                                refused("Could not read that link. Send the track name instead."))));
  }

  public String pause(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.player().getPlayingTrack() == null) {
      return "Nothing is playing.";
    }
    music.player().setPaused(true);
    return "Paused.";
  }

  public String resume(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.player().getPlayingTrack() == null) {
      return "Nothing to resume.";
    }
    music.player().setPaused(false);
    return "Resumed.";
  }

  public String stop(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.player().getPlayingTrack() == null) {
      return "Nothing is playing.";
    }
    music.player().stopTrack();
    music.scheduler().queueView().clear();
    return "Stopped and cleared the queue.";
  }

  public String skip(long guildId, Optional<String> target) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.scheduler().current().isEmpty()) {
      return "Nothing to skip.";
    }
    if (target.isEmpty() || target.orElseThrow().isBlank()) {
      music.scheduler().nextTrack();
      return "Skipped.";
    }
    Optional<Integer> index = findQueued(music, target.orElseThrow());
    if (index.isEmpty()) {
      return "No queued track matches that.";
    }
    for (int i = 1; i < index.orElseThrow(); i++) {
      music.scheduler().queueView().poll();
    }
    music.scheduler().nextTrack();
    return "Skipped.";
  }

  public String remove(long guildId, String target) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null) {
      return "The queue is empty.";
    }
    Optional<Integer> index = findQueued(music, target);
    if (index.isEmpty()) {
      return "No queued track matches that.";
    }
    List<TrackCandidate> queued = music.scheduler().queueView().snapshot();
    TrackCandidate removed = queued.get(index.orElseThrow() - 1);
    music.scheduler().queueView().removeAt(index.orElseThrow() - 1);
    return "Removed " + removed.title() + ".";
  }

  public QueueView queueView(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null) {
      return new QueueView(Optional.empty(), List.of());
    }
    return new QueueView(
        music.scheduler().current(), music.scheduler().queueView().snapshot());
  }

  public boolean isIdle(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    return music == null
        || (music.player().getPlayingTrack() == null
            && music.scheduler().queueView().size() == 0);
  }

  public String leave(long guildId) {
    GuildMusicManager music = managers.remove(guildId);
    if (music != null) {
      music.player().stopTrack();
      music.scheduler().queueView().clear();
    }
    connector.disconnect(guildId);
    return "Left the voice channel.";
  }

  private Optional<Integer> findQueued(GuildMusicManager music, String target) {
    String trimmed = target.strip();
    List<TrackCandidate> queued = music.scheduler().queueView().snapshot();
    try {
      int number = Integer.parseInt(trimmed);
      if (number >= 1 && number <= queued.size()) {
        return Optional.of(number);
      }
      return Optional.empty();
    } catch (NumberFormatException notANumber) {
      String lowered = trimmed.toLowerCase();
      for (int i = 0; i < queued.size(); i++) {
        TrackCandidate candidate = queued.get(i);
        String haystack = (candidate.title() + " " + candidate.artist()).toLowerCase();
        if (haystack.contains(lowered)) {
          return Optional.of(i + 1);
        }
      }
      return Optional.empty();
    }
  }

  private CompletableFuture<PlayResult> playQuery(
      long guildId, long voiceChannelId, PlayQuery query) {
    return resolver
        .resolve(query)
        .thenApply(
            candidates -> {
              if (candidates.isEmpty()) {
                return refused("Nothing found. Try another name or link.");
              }
              TrackCandidate best = candidates.get(0);
              connector.connect(guildId, voiceChannelId);
              TrackCandidate clone = cloneOf(best);
              int accepted = musicManager(guildId).enqueueAll(List.of(clone));
              if (accepted == 0) {
                return refused("The queue is full.");
              }
              return new PlayResult(true, "", best);
            })
        .exceptionally(
            failure -> {
              log.warn("Playback failed", failure);
              return refused("Search failed. Try again later.");
            });
  }

  private static TrackCandidate cloneOf(TrackCandidate candidate) {
    return new TrackCandidate(
        candidate.source(),
        candidate.title(),
        candidate.artist(),
        candidate.duration(),
        candidate.uri(),
        candidate.score(),
        candidate.track().makeClone());
  }

  private static PlayResult refused(String message) {
    return new PlayResult(false, message, null);
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
    return managers.computeIfAbsent(
        guildId, id -> new GuildMusicManager(playerManager, queueMax));
  }
}
