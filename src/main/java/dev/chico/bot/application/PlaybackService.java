package dev.chico.bot.application;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import dev.chico.bot.audio.AudioPlayerSendHandler;
import dev.chico.bot.audio.GuildMusicManager;
import dev.chico.bot.config.Config;
import dev.chico.bot.security.RateLimiter;
import dev.chico.bot.source.AppleMusicClient;
import dev.chico.bot.source.CircuitBreaker;
import dev.chico.bot.source.DefaultTrackResolver;
import dev.chico.bot.source.LavaplayerTrackLoader;
import dev.chico.bot.source.MetadataLookup;
import dev.chico.bot.source.PatchedTvClient;
import dev.chico.bot.source.PlayQuery;
import dev.chico.bot.source.Ranker;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.SpotifyClient;
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

  private static final int MAX_SEARCH_RESULTS = 10;
  private static final URI ITUNES_LOOKUP = URI.create("https://itunes.apple.com/lookup");
  private static final String SPOTIFY_NOTICE =
      "Spotify is only partially supported right now (title-only fallback), so the search may"
          + " pick a different version.";
  private static final String SPOTIFY_COLLECTION_REFUSAL =
      "That Spotify link is not a single track. Send a track link or the song name.";
  private static final String UNREADABLE_LINK =
      "Could not read that link. Send the track name instead.";

  private static final Logger log = LoggerFactory.getLogger(PlaybackService.class);
  private static final java.util.concurrent.ExecutorService RESOLVE_EXECUTOR =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

  /** Outcome of a play request: the queued track or a message for the user. */
  public record PlayResult(
      boolean queued, String message, TrackCandidate candidate, PlaylistPreview playlist) {

    public PlayResult(boolean queued, String message, TrackCandidate candidate) {
      this(queued, message, candidate, null);
    }
  }

  /** Current track plus the waiting queue, both without audio handles. */
  public record QueueView(Optional<TrackCandidate> current, List<TrackCandidate> queued) {
  }

  private final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
  private final Map<Long, GuildMusicManager> managers = new ConcurrentHashMap<>();
  private final VoiceConnector connector;
  private final TrackResolver resolver;
  private final MetadataLookup metadata;
  private final PlaylistFlow playlistFlow;
  private final Map<Source, TrackLoader> loaders;
  private final Ranker ranker;
  private final AudioRecovery audioRecovery;
  private final VoteStore votes;
  private final java.util.concurrent.Semaphore fallbackSlots;
  private final Map<Long, Map<Long, Integer>> failureAttempts = new ConcurrentHashMap<>();
  private final RateLimiter rateLimiter = new RateLimiter();
  private final Clock clock;
  private final int queueMax;
  private final int playCooldownS;
  private final int resolveConcurrency;
  private final Duration resolveTimeout;
  private volatile java.util.function.Consumer<Long> queueChanged = guildId -> {
  };
  private volatile java.util.function.BiConsumer<Long, String> trackFailureListener =
      (guildId, message) -> {
      };

  public PlaybackService(VoiceConnector connector, Config config) {
    this(connector, config, Clock.systemUTC());
  }

  PlaybackService(VoiceConnector connector, Config config, Clock clock) {
    this.connector = connector;
    this.clock = clock;
    this.queueMax = config.queueMax();
    this.playCooldownS = config.playCooldownS();
    this.resolveConcurrency = config.resolveConcurrency();
    this.resolveTimeout = Duration.ofMillis(config.resolveTimeoutMs());
    Duration resolveTimeout = this.resolveTimeout;
    YoutubeAudioSourceManager youtube = createYoutubeSource(config);
    playerManager.registerSourceManager(youtube);
    playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
    Map<Source, TrackLoader> createdLoaders = new EnumMap<>(Source.class);
    createdLoaders.put(
        Source.YOUTUBE,
        new LavaplayerTrackLoader(Source.YOUTUBE, playerManager, MAX_SEARCH_RESULTS, resolveTimeout));
    createdLoaders.put(
        Source.SOUNDCLOUD,
        new LavaplayerTrackLoader(
            Source.SOUNDCLOUD, playerManager, MAX_SEARCH_RESULTS, resolveTimeout));
    this.loaders = Map.copyOf(createdLoaders);
    this.ranker = new Ranker(config.matchThreshold());
    this.resolver =
        new DefaultTrackResolver(
            createdLoaders,
            ranker,
            new CircuitBreaker(
                config.sourceFailureThreshold(),
                Duration.ofSeconds(config.sourceDegradedCooldownS()),
                clock),
            resolveTimeout,
            config.parallelSearch());
    SpotifyClient spotify =
        config.spotifyClientId()
            .map(
                id ->
                    new SpotifyClient(
                        id,
                        config.spotifyClientSecret().orElseThrow(),
                        config.spotifyRefreshToken(),
                        resolveTimeout))
            .orElse(null);
    AppleMusicClient apple =
        config
            .appleMusicDeveloperToken()
            .map(
                token ->
                    new AppleMusicClient(
                        token,
                        config.appleMusicStorefront(),
                        config.appleMusicUserToken(),
                        resolveTimeout))
            .orElse(null);
    this.metadata = new MetadataLookup(ITUNES_LOOKUP, resolveTimeout, spotify, apple);
    this.playlistFlow =
        new PlaylistFlow(
            resolver,
            metadata,
            Duration.ofSeconds(config.importSessionTtlS()),
            clock);
    if (spotify != null) {
      spotify
          .status()
          .thenAccept(
              status -> {
                if (status.userMode()) {
                  log.info(
                      "Spotify user mode enabled for {}",
                      status.displayName() == null
                          ? "the configured account"
                          : status.displayName());
                } else if (status.configured()) {
                  log.info("Spotify user mode unavailable: {}", status.error());
                } else {
                  log.info("Spotify user mode not configured; using public metadata");
                }
              });
    }
    this.audioRecovery =
        new AudioRecovery(ranker, this::reloadTrack, this::searchFallback);
    this.votes = new VoteStore(clock);
    this.fallbackSlots =
        new java.util.concurrent.Semaphore(Math.max(1, config.resolveConcurrency()));
  }

  PlaybackService(
      VoiceConnector connector, Config config, Clock clock, TrackResolver resolver) {
    this(
        connector,
        config,
        clock,
        resolver,
        new MetadataLookup(
            ITUNES_LOOKUP, Duration.ofMillis(config.resolveTimeoutMs())));
  }

  PlaybackService(
      VoiceConnector connector,
      Config config,
      Clock clock,
      TrackResolver resolver,
      MetadataLookup metadata) {
    this.connector = connector;
    this.clock = clock;
    this.queueMax = config.queueMax();
    this.playCooldownS = config.playCooldownS();
    this.resolveConcurrency = config.resolveConcurrency();
    this.resolveTimeout = Duration.ofMillis(config.resolveTimeoutMs());
    this.resolver = resolver;
    this.metadata = metadata;
    this.playlistFlow =
        new PlaylistFlow(
            resolver,
            metadata,
            Duration.ofSeconds(config.importSessionTtlS()),
            clock);
    this.loaders = Map.of();
    this.ranker = new Ranker(config.matchThreshold());
    this.audioRecovery =
        new AudioRecovery(ranker, this::reloadTrack, this::searchFallback);
    this.votes = new VoteStore(clock);
    this.fallbackSlots =
        new java.util.concurrent.Semaphore(Math.max(1, config.resolveConcurrency()));
  }

  public AudioPlayerSendHandler sendHandler(long guildId) {
    return musicManager(guildId).sendHandler();
  }

  /** Discord-side hook: called after every queue/playback change. */
  public void setQueueChangeListener(java.util.function.Consumer<Long> listener) {
    this.queueChanged = listener == null ? guildId -> {
    } : listener;
  }

  /** Discord-side hook: user-visible report when a track cannot be played. */
  public void setTrackFailureListener(
      java.util.function.BiConsumer<Long, String> listener) {
    this.trackFailureListener = listener == null ? (guildId, message) -> {
    } : listener;
  }

  private void notifyChanged(long guildId) {
    try {
      queueChanged.accept(guildId);
    } catch (RuntimeException e) {
      log.debug("queue change listener failed", e);
    }
  }

  /** Resolves one query to ordered candidates. Used by the import preview. */
  public CompletableFuture<List<TrackCandidate>> resolve(PlayQuery query) {
    return resolver.resolve(query);
  }

  /** Connects to a voice channel. Used by the import preview. */
  public boolean connect(long guildId, long voiceChannelId) {
    return connector.connect(guildId, voiceChannelId);
  }

  /**
   * Enqueues already-resolved candidates in order with one call, cloning each
   * playable track. Returns how many were accepted before the queue filled.
   */
  public int enqueueResolved(long guildId, List<TrackCandidate> candidates) {
    return enqueueResolved(guildId, candidates, 0L);
  }

  /** Same, attributing every accepted item to {@code addedBy}. */
  public int enqueueResolved(long guildId, List<TrackCandidate> candidates, long addedBy) {
    int accepted =
        musicManager(guildId)
            .enqueueAll(candidates.stream().map(PlaybackService::cloneOf).toList(), addedBy);
    notifyChanged(guildId);
    return accepted;
  }

  /** Playlist-confirm path only: no QUEUE_MAX cap. */
  public int enqueueResolvedUnbounded(
      long guildId, List<TrackCandidate> candidates, long addedBy) {
    int accepted =
        musicManager(guildId)
            .enqueueAllUnbounded(
                candidates.stream().map(PlaybackService::cloneOf).toList(), addedBy);
    notifyChanged(guildId);
    return accepted;
  }

  public Optional<PlaylistSession> playlistSession(java.util.UUID id) {
    return playlistFlow.get(id);
  }

  public void cancelPlaylist(java.util.UUID id) {
    playlistFlow.remove(id);
  }

  /**
   * Confirms a playlist preview: either plays the single linked track or
   * enqueues the whole list (the only unbounded queue path).
   */
  public CompletableFuture<String> confirmPlaylist(
      java.util.UUID sessionId,
      long userId,
      long guildId,
      boolean onlySingle,
      long voiceChannelId) {
    Optional<PlaylistSession> found = playlistFlow.get(sessionId);
    if (found.isEmpty()) {
      return CompletableFuture.completedFuture("That playlist expired.");
    }
    PlaylistSession session = found.orElseThrow();
    if (session.userId() != userId || session.guildId() != guildId) {
      return CompletableFuture.completedFuture("Only the requester can do that.");
    }
    if (onlySingle) {
      if (session.singleTrackUri() == null) {
        return CompletableFuture.completedFuture("That link has no single track.");
      }
      playlistFlow.remove(sessionId);
      return playQuery(
              guildId,
              voiceChannelId,
              userId,
              new UrlQuery(Source.YOUTUBE, session.singleTrackUri()))
          .thenApply(result -> result.queued() ? "Playing that track." : result.message());
    }
    playlistFlow.remove(sessionId);
    if (!connector.connect(guildId, voiceChannelId)) {
      return CompletableFuture.completedFuture("Could not join that voice channel.");
    }
    if (session.items().isEmpty()) {
      return CompletableFuture.completedFuture("That playlist has no tracks.");
    }
    List<TrackCandidate> loaded =
        session.items().stream()
            .filter(item -> item.candidate() != null)
            .map(PlaylistItem::candidate)
            .toList();
    List<PlaylistItem> metadataOnly =
        session.items().stream().filter(item -> item.candidate() == null).toList();
    if (metadataOnly.isEmpty()) {
      int enqueued = enqueueResolvedUnbounded(guildId, loaded, userId);
      return CompletableFuture.completedFuture("Enqueued " + enqueued + ".");
    }
    return resolvePlaylistItems(metadataOnly)
        .thenApply(
            resolved -> {
              List<TrackCandidate> all = new java.util.ArrayList<>(loaded);
              all.addAll(resolved);
              int enqueued = enqueueResolvedUnbounded(guildId, all, userId);
              int notFound = session.items().size() - all.size();
              String report = "Enqueued " + enqueued + ".";
              if (notFound > 0) {
                report += " " + notFound + " not found.";
              }
              return report;
            });
  }

  private CompletableFuture<List<TrackCandidate>> resolvePlaylistItems(
      List<PlaylistItem> items) {
    java.util.concurrent.Semaphore slots =
        new java.util.concurrent.Semaphore(Math.max(1, resolveConcurrency));
    java.util.List<CompletableFuture<TrackCandidate>> futures = new java.util.ArrayList<>();
    for (PlaylistItem item : items) {
      futures.add(
          CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      slots.acquire();
                      try {
                        List<TrackCandidate> candidates =
                            resolver
                                .resolve(new TextQuery(item.searchText()))
                                .get(resolveTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                        return candidates.isEmpty() ? null : candidates.get(0);
                      } finally {
                        slots.release();
                      }
                    } catch (Exception failure) {
                      return null;
                    }
                  },
                  RESOLVE_EXECUTOR)
              .thenApply(candidate -> (TrackCandidate) candidate));
    }
    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenApply(
            ignored ->
                futures.stream()
                    .map(CompletableFuture::join)
                    .filter(candidate -> candidate != null)
                    .toList());
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
        return playQuery(guildId, voiceChannelId, userId, new TextQuery(input));
      }
      return CompletableFuture.completedFuture(refused(refusalMessage(rejected.reason(), input)));
    }
    UrlDecision.Accepted accepted = (UrlDecision.Accepted) decision;
    if (accepted.playlist()) {
      return playlistFlow
          .preview(userId, guildId, accepted)
          .thenApply(
              preview ->
                  preview.sessionId() == null
                      ? refused(preview.error())
                      : new PlayResult(false, "", null, preview));
    }
    if (accepted.source() == Source.SPOTIFY) {
      return playSpotify(guildId, voiceChannelId, userId, accepted);
    }
    UrlQuery query = new UrlQuery(accepted.source(), accepted.uri());
    if (accepted.source() == Source.YOUTUBE || accepted.source() == Source.SOUNDCLOUD) {
      return playQuery(guildId, voiceChannelId, userId, query)
          .thenApply(
              result -> {
                if (!result.queued() && accepted.source() == Source.SOUNDCLOUD) {
                  return refused(
                      "SoundCloud couldn't load that track. Try a YouTube link"
                          + " or search for the track name.");
                }
                return result;
              });
    }
    return metadata
        .artistTitle(query)
        .thenCompose(
            text ->
                text.map(
                        found -> playQuery(guildId, voiceChannelId, userId, new TextQuery(found)))
                    .orElseGet(
                        () ->
                            CompletableFuture.completedFuture(refused(UNREADABLE_LINK))));
  }

  private CompletableFuture<PlayResult> playSpotify(
      long guildId, long voiceChannelId, long userId, UrlDecision.Accepted accepted) {
    if (!metadata.spotifyEnabled()) {
      return playSpotifyOembed(guildId, voiceChannelId, userId, accepted);
    }
    return metadata
        .spotifyContent(new UrlQuery(Source.SPOTIFY, accepted.uri()))
        .thenCompose(
            content -> {
              if (content.error() != null) {
                return CompletableFuture.completedFuture(refused(content.error()));
              }
              if (content.kind() == SpotifyClient.Kind.PLAYLIST) {
                PlaylistPreview preview =
                    playlistFlow.fromContent(
                        userId, guildId, content, accepted.singleTrack());
                return CompletableFuture.completedFuture(
                    preview.sessionId() == null
                        ? refused(preview.error())
                        : new PlayResult(false, "", null, preview));
              }
              if (content.kind() == SpotifyClient.Kind.TRACK
                  && !content.tracks().isEmpty()) {
                return playSpotifyText(
                    guildId, voiceChannelId, userId, content.tracks().get(0), true);
              }
              return playSpotifyOembed(guildId, voiceChannelId, userId, accepted);
            })
        .exceptionally(
            failure -> {
              log.warn("Spotify metadata failed", failure);
              return refused(UNREADABLE_LINK);
            });
  }

  private CompletableFuture<PlayResult> playSpotifyOembed(
      long guildId, long voiceChannelId, long userId, UrlDecision.Accepted accepted) {
    return metadata
        .spotifyTitle(accepted.uri())
        .thenCompose(
            found ->
                switch (found.kind()) {
                  case FOUND ->
                      playSpotifyText(
                          guildId, voiceChannelId, userId, found.title(), found.viaApi());
                  case NOT_A_TRACK ->
                      CompletableFuture.completedFuture(refused(SPOTIFY_COLLECTION_REFUSAL));
                  case FAILED ->
                      CompletableFuture.completedFuture(refused(UNREADABLE_LINK));
                })
        .exceptionally(
            failure -> {
              log.warn("Spotify metadata failed", failure);
              return refused(UNREADABLE_LINK);
            });
  }

  private CompletableFuture<PlayResult> playSpotifyText(
      long guildId, long voiceChannelId, long userId, String title, boolean viaApi) {
    return playQuery(guildId, voiceChannelId, userId, new TextQuery(title))
        .thenApply(
            played ->
                played.queued()
                    ? new PlayResult(
                        true,
                        spotifyNotice(viaApi),
                        played.candidate(),
                        played.playlist())
                    : played);
  }

  static String spotifyNotice(boolean viaApi) {
    return viaApi ? "" : SPOTIFY_NOTICE;
  }

  public String pause(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.player().getPlayingTrack() == null) {
      return "Nothing is playing.";
    }
    music.player().setPaused(true);
    notifyChanged(guildId);
    return "Paused.";
  }

  public String resume(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.player().getPlayingTrack() == null) {
      return "Nothing to resume.";
    }
    music.player().setPaused(false);
    notifyChanged(guildId);
    return "Resumed.";
  }

  /** Loop target semantics: empty/1 = current, "all", "disable" or a number. */
  public String loop(long guildId, Optional<String> target) {
    GuildMusicManager music = managers.get(guildId);
    String raw = target.orElse("").strip();
    String lowered = raw.toLowerCase(java.util.Locale.ROOT);
    if (lowered.equals("disable")) {
      if (music != null) {
        music.scheduler().clearLoop();
        notifyChanged(guildId);
      }
      return "Loop disabled.";
    }
    if (music == null) {
      return "Nothing is playing.";
    }
    if (lowered.isEmpty() || lowered.equals("1")) {
      boolean looped = music.scheduler().loopCurrent().isPresent();
      if (looped) {
        notifyChanged(guildId);
      }
      return looped ? "Looping current track." : "Nothing is playing.";
    }
    if (lowered.equals("all")) {
      Optional<Integer> size = music.scheduler().loopAll();
      if (size.isPresent()) {
        notifyChanged(guildId);
      }
      return size
          .map(value -> "Looping queue (" + value + " tracks).")
          .orElse("Nothing is playing.");
    }
    int position;
    try {
      position = Integer.parseInt(lowered);
    } catch (NumberFormatException notANumber) {
      return "Use `/loop`, `/loop <number>`, `/loop all`, `/loop disable` or `/unloop`.";
    }
    if (position < 1) {
      return "No queued track matches that.";
    }
    return music
        .scheduler()
        .loopAt(position)
        .map(
            marked -> {
              notifyChanged(guildId);
              return "Looping track "
                  + position
                  + " - "
                  + dev.chico.bot.security.TextSanitizer.escape(marked.title())
                  + ".";
            })
        .orElse("No queued track matches that.");
  }

  public dev.chico.bot.audio.TrackScheduler.LoopView loopView(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    return music == null
        ? dev.chico.bot.audio.TrackScheduler.LoopView.off()
        : music.scheduler().loopView();
  }

  public String unloop(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null
        || music.scheduler().loopView().mode()
            == dev.chico.bot.audio.TrackScheduler.LoopMode.OFF) {
      return "No loop is active.";
    }
    music.scheduler().clearLoop();
    notifyChanged(guildId);
    return "Loop disabled.";
  }

  public String stop(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.player().getPlayingTrack() == null) {
      return "Nothing is playing.";
    }
    synchronized (music) {
      music.scheduler().stopAndClear();
    }
    votes.clearGuild(guildId);
    failureAttempts.remove(guildId);
    notifyChanged(guildId);
    return "Stopped and cleared the queue.";
  }

  public String skip(long guildId, Optional<String> target) {
    return skip(guildId, target, 0L, java.util.Set.of());
  }

  /**
   * Skips immediately when the caller owns the target or the call has fewer
   * than three humans; otherwise records a vote on the target identity.
   */
  public String skip(
      long guildId, Optional<String> target, long userId, java.util.Set<Long> voiceHumans) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null || music.scheduler().current().isEmpty()) {
      return "Nothing to skip.";
    }
    String trimmed = target.orElse("").strip();
    synchronized (music.scheduler()) {
      Optional<TrackCandidate> currentNow = music.scheduler().current();
      if (currentNow.isEmpty()) {
        return "Nothing to skip.";
      }
      TrackCandidate oldCurrent = currentNow.orElseThrow();
      if (trimmed.isEmpty() || isDisplayOne(trimmed)) {
        String voteReply =
            voteIfNeeded(guildId, VoteStore.Action.SKIP, oldCurrent, userId, voiceHumans);
        if (voteReply != null) {
          return voteReply;
        }
        music.scheduler().clearLoop();
        music.scheduler().nextTrack();
        Optional<TrackCandidate> now = music.scheduler().current();
        if (now.isEmpty()) {
          notifyChanged(guildId);
          return "Skipped " + escapeTitle(oldCurrent) + ". Nothing is playing.";
        }
        notifyChanged(guildId);
        return "Skipped to "
            + escapeTitle(now.orElseThrow())
            + ".\n"
            + skippedList(List.of(oldCurrent), false);
      }
      Optional<Integer> index = findQueued(music, trimmed);
      if (index.isEmpty()) {
        return "No queued track matches that.";
      }
      int queueIndex = index.orElseThrow() - 1;
      List<TrackCandidate> queued = music.scheduler().queueView().snapshot();
      if (queueIndex < 0 || queueIndex >= queued.size()) {
        return "No queued track matches that.";
      }
      TrackCandidate actionTarget = queued.get(queueIndex);
      String voteReply =
          voteIfNeeded(guildId, VoteStore.Action.SKIP, actionTarget, userId, voiceHumans);
      if (voteReply != null) {
        return voteReply;
      }
      List<TrackCandidate> skipped = new java.util.ArrayList<>();
      skipped.add(oldCurrent);
      for (int i = 0; i < queueIndex && i < queued.size(); i++) {
        skipped.add(queued.get(i));
      }
      music.scheduler().clearLoop();
      music.scheduler().skipTo(queueIndex);
      Optional<TrackCandidate> now = music.scheduler().current();
      if (now.isEmpty()) {
        notifyChanged(guildId);
        return "Skipped. Nothing is playing.\n" + skippedList(skipped, true);
      }
      notifyChanged(guildId);
      String targetLabel =
          isNumeric(trimmed)
              ? (index.orElseThrow() + 1) + " - " + escapeTitle(now.orElseThrow())
              : escapeTitle(now.orElseThrow());
      return "Skipped to " + targetLabel + ".\n" + skippedList(skipped, true);
    }
  }

  /** Null means "execute now"; otherwise the pending-vote reply. */
  private String voteIfNeeded(
      long guildId,
      VoteStore.Action action,
      TrackCandidate target,
      long userId,
      java.util.Set<Long> voiceHumans) {
    if (voiceHumans.size() < 3 || target.addedBy() == 0 || target.addedBy() == userId) {
      votes.retireItem(guildId, target.itemId());
      return null;
    }
    votes.pruneGuild(guildId, liveItemIds(guildId));
    VoteStore.Result result =
        votes.vote(guildId, action, target.itemId(), userId, voiceHumans.size());
    if (result.passed()) {
      return null;
    }
    String verb = action == VoteStore.Action.SKIP ? "Skipped" : "Removed";
    return verb + " " + result.votes() + "/" + result.needed() + ".";
  }

  private java.util.Set<Long> liveItemIds(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null) {
      return java.util.Set.of();
    }
    java.util.Set<Long> ids = new java.util.HashSet<>();
    music.scheduler().current().ifPresent(candidate -> ids.add(candidate.itemId()));
    for (TrackCandidate candidate : music.scheduler().queueView().snapshot()) {
      ids.add(candidate.itemId());
    }
    return ids;
  }

  private static boolean isDisplayOne(String target) {
    try {
      return Integer.parseInt(target) == 1;
    } catch (NumberFormatException notANumber) {
      return false;
    }
  }

  private static boolean isNumeric(String target) {
    try {
      Integer.parseInt(target);
      return true;
    } catch (NumberFormatException notANumber) {
      return false;
    }
  }

  private static String escapeTitle(TrackCandidate candidate) {
    return dev.chico.bot.security.TextSanitizer.escape(candidate.title());
  }

  /**
   * Shared skip report list: first ten titles keep the message under the
   * 2000-character limit even after playlist-sized queues.
   */
  private static String skippedList(List<TrackCandidate> skipped, boolean numbered) {
    StringBuilder report = new StringBuilder("Skipped:");
    int shown = Math.min(10, skipped.size());
    for (int i = 0; i < shown; i++) {
      report.append('\n');
      if (numbered) {
        report.append(i + 1).append(". ");
      }
      report.append(escapeTitle(skipped.get(i)));
    }
    if (skipped.size() > shown) {
      report.append("\n…and ").append(skipped.size() - shown).append(" more.");
    }
    return report.toString();
  }

  public String remove(long guildId, String target) {
    return remove(guildId, target, 0L, java.util.Set.of());
  }

  /**
   * Removes immediately when the caller owns the target or the call has fewer
   * than three humans; otherwise records a vote on the target identity.
   */
  public String remove(
      long guildId, String target, long userId, java.util.Set<Long> voiceHumans) {
    GuildMusicManager music = managers.get(guildId);
    if (music == null) {
      return "The queue is empty.";
    }
    if (target.isBlank()) {
      return "No queued track matches that.";
    }
    String trimmed = target.strip();
    try {
      if (Integer.parseInt(trimmed) == 1) {
        return "Cannot remove the current track. Use /skip to skip it.";
      }
    } catch (NumberFormatException notANumber) {
      // Fall through to name lookup.
    }
    String removedTitle;
    int displayPosition;
    boolean clearedLoop = false;
    // POR QUE: lookup and removal share one lock so a track end between
    // them cannot shift the target or resurrect the confirmation.
    synchronized (music.scheduler()) {
      Optional<Integer> index = findQueued(music, target);
      if (index.isEmpty()) {
        return "No queued track matches that.";
      }
      List<TrackCandidate> queued = music.scheduler().queueView().snapshot();
      int queueIndex = index.orElseThrow() - 1;
      if (queueIndex < 0 || queueIndex >= queued.size()) {
        return "No queued track matches that.";
      }
      String voteReply =
          voteIfNeeded(
              guildId,
              VoteStore.Action.REMOVE,
              queued.get(queueIndex),
              userId,
              voiceHumans);
      if (voteReply != null) {
        return voteReply;
      }
      clearedLoop = music.scheduler().loopMarked(queued.get(queueIndex).itemId());
      Optional<TrackCandidate> removed = music.scheduler().removeAt(queueIndex);
      if (removed.isEmpty()) {
        return "No queued track matches that.";
      }
      removedTitle = removed.orElseThrow().title();
      displayPosition = index.orElseThrow() + 1;
      try {
        int typed = Integer.parseInt(trimmed);
        if (typed >= 2 && typed <= music.scheduler().queueView().size() + 2) {
          displayPosition = typed;
        }
      } catch (NumberFormatException notANumber) {
        // Name match keeps index+1.
      }
    }
    notifyChanged(guildId);
    String loopNote = clearedLoop ? " Loop cleared (track removed)." : "";
    return "Removed "
        + dev.chico.bot.security.TextSanitizer.escape(removedTitle)
        + " (position "
        + displayPosition
        + ")."
        + loopNote;
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

  /** A track exists in the player and is paused. */
  public boolean isPaused(long guildId) {
    GuildMusicManager music = managers.get(guildId);
    return music != null
        && music.player().getPlayingTrack() != null
        && music.player().isPaused();
  }

  public String leave(long guildId) {
    GuildMusicManager music = managers.remove(guildId);
    failureAttempts.remove(guildId);
    if (music != null) {
      synchronized (music) {
        music.scheduler().stopAndClear();
      }
    }
    connector.disconnect(guildId);
    votes.clearGuild(guildId);
    notifyChanged(guildId);
    return "Left the voice channel.";
  }

  private Optional<Integer> findQueued(GuildMusicManager music, String target) {
    String trimmed = target.strip();
    List<TrackCandidate> queued = music.scheduler().queueView().snapshot();
    try {
      int number = Integer.parseInt(trimmed);
      if (number == 1) {
        return Optional.empty();
      }
      if (number >= 2 && number <= queued.size() + 1) {
        return Optional.of(number - 1);
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
      long guildId, long voiceChannelId, long userId, PlayQuery query) {
    return resolver
        .resolve(query)
        .thenApply(
            candidates -> {
              if (candidates.isEmpty()) {
                return refused("Nothing found. Try another name or link.");
              }
              TrackCandidate best = candidates.get(0);
              if (!connector.connect(guildId, voiceChannelId)) {
                return refused("Could not join that voice channel.");
              }
              TrackCandidate clone = cloneOf(best);
              int accepted = musicManager(guildId).enqueueAll(List.of(clone), userId);
              if (accepted == 0) {
                return refused("The queue is full.");
              }
              notifyChanged(guildId);
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

  private static String refusalMessage(UrlDecision.Reason reason, String input) {
    return switch (reason) {
      case PLAYLIST ->
          "That collection link is not supported. Send a single track or a supported playlist.";
      case SPOTIFY -> "Spotify episodes are not supported. Send a track link or the song name.";
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
        guildId,
        id -> {
          GuildMusicManager manager =
              new GuildMusicManager(
                  playerManager,
                  queueMax,
                  () -> notifyChanged(id),
                  (scheduler, failed, failure) ->
                      handleTrackFailure(id, scheduler, failed, failure));
          manager
              .scheduler()
              .setStartedHandler(candidate -> clearFailureAttempt(id, candidate.itemId()));
          return manager;
        });
  }

  private void clearFailureAttempt(long guildId, long itemId) {
    Map<Long, Integer> attempts = failureAttempts.get(guildId);
    if (attempts != null) {
      attempts.remove(itemId);
      if (attempts.isEmpty()) {
        failureAttempts.remove(guildId, attempts);
      }
    }
  }

  private void handleTrackFailure(
      long guildId,
      dev.chico.bot.audio.TrackScheduler scheduler,
      TrackCandidate failed,
      com.sedmelluq.discord.lavaplayer.tools.FriendlyException exception) {
    int attempt =
        failureAttempts
            .computeIfAbsent(guildId, key -> new ConcurrentHashMap<>())
            .merge(failed.itemId(), 1, Integer::sum);
    audioRecovery
        .recover(failed, exception, attempt)
        .thenAccept(
            recovered -> {
              if (recovered.isPresent()) {
                scheduler.replaceCurrent(
                    recovered
                        .orElseThrow()
                        .withQueueIdentity(failed.itemId(), failed.addedBy()));
              } else if (scheduler.skipFailed(failed.itemId())) {
                notifyTrackFailure(guildId, failed.title());
              }
            })
        .exceptionally(
            failure -> {
              if (scheduler.skipFailed(failed.itemId())) {
                notifyTrackFailure(guildId, failed.title());
              }
              return null;
            });
  }

  private CompletableFuture<TrackCandidate> reloadTrack(TrackCandidate failed) {
    TrackLoader loader = loaders.get(failed.source());
    if (loader == null || failed.uri() == null) {
      return CompletableFuture.completedFuture(null);
    }
    return loader
        .loadUrl(failed.uri())
        .thenApply(
            candidates ->
                candidates.isEmpty()
                    ? null
                    : candidates
                        .get(0)
                        .withQueueIdentity(failed.itemId(), failed.addedBy()))
        .exceptionally(failure -> null);
  }

  private CompletableFuture<List<TrackCandidate>> searchFallback(String text) {
    // Fail fast when all resolve slots are busy; the scheduler reports a
    // named skip instead of blocking the Lavaplayer callback thread.
    if (!fallbackSlots.tryAcquire()) {
      return CompletableFuture.completedFuture(List.of());
    }
    try {
      return resolver
          .resolve(new TextQuery(text))
          .whenComplete((candidates, failure) -> fallbackSlots.release());
    } catch (RuntimeException failure) {
      fallbackSlots.release();
      return CompletableFuture.completedFuture(List.of());
    }
  }

  private void notifyTrackFailure(long guildId, String title) {
    try {
      trackFailureListener.accept(
          guildId,
          "Skipped (unplayable): "
              + dev.chico.bot.security.TextSanitizer.escape(title)
              + ".");
    } catch (RuntimeException e) {
      log.debug("track failure listener failed", e);
    }
  }
}
