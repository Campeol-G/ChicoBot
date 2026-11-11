package dev.chico.bot.discord;

import dev.chico.bot.application.ImportFromImage;
import dev.chico.bot.application.ImportSession;
import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.config.Config;
import dev.chico.bot.parser.ParsedTrack;
import dev.chico.bot.source.OcrQuery;
import dev.chico.bot.source.TrackCandidate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

/** "Play all", "Remove items" and "Cancel" for import previews. Owner-only. */
public final class PreviewButtons extends ListenerAdapter {

  private static final Logger log = LoggerFactory.getLogger(PreviewButtons.class);

  static final String PLAY_PREFIX = "import-play:";
  static final String CANCEL_PREFIX = "import-cancel:";
  static final String REMOVE_PREFIX = "import-remove:";

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private final ImportSession.Store sessions;
  private final PlaybackService playback;
  private final Semaphore resolveSlots;
  private final Duration resolveTimeout;
  private final java.util.Set<UUID> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public PreviewButtons(
      ImportSession.Store sessions, PlaybackService playback, Config config) {
    this.sessions = sessions;
    this.playback = playback;
    this.resolveSlots = new Semaphore(config.resolveConcurrency());
    this.resolveTimeout = Duration.ofMillis(config.resolveTimeoutMs());
  }

  /** Preview embed plus Play all / Cancel buttons and the remove select menu. */
  public static MessageEditData previewMessage(
      UUID sessionId, List<ParsedTrack> tracks, int totalDetected) {
    return previewEdit(sessionId, tracks, totalDetected, 0, false, 0);
  }

  /** Preview embed with dropped-image count. */
  public static MessageEditData previewMessage(
      UUID sessionId, List<ParsedTrack> tracks, int totalDetected, int droppedImages) {
    return previewEdit(sessionId, tracks, totalDetected, droppedImages, false, 0);
  }

  /** Preview embed with the non-music warning banner. */
  public static MessageEditData previewMessage(
      UUID sessionId,
      List<ParsedTrack> tracks,
      int totalDetected,
      int droppedImages,
      boolean warned,
      int suspiciousDropped) {
    return previewEdit(
        sessionId, tracks, totalDetected, droppedImages, warned, suspiciousDropped);
  }

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    String id = event.getComponentId();
    if (id.startsWith(PLAY_PREFIX)) {
      onPlayAll(event, sessionId(id, PLAY_PREFIX));
    } else if (id.startsWith(CANCEL_PREFIX)) {
      onCancel(event, sessionId(id, CANCEL_PREFIX));
    }
  }

  @Override
  public void onStringSelectInteraction(StringSelectInteractionEvent event) {
    if (!event.getComponentId().startsWith(REMOVE_PREFIX)) {
      return;
    }
    UUID sessionId = sessionId(event.getComponentId(), REMOVE_PREFIX);
    Optional<ImportSession> session = sessions.get(sessionId);
    if (session.isEmpty()) {
      event.reply("This import expired.").setEphemeral(true).queue();
      return;
    }
    if (session.orElseThrow().userId() != event.getUser().getIdLong()) {
      event.reply("Only the importer can do that.").setEphemeral(true).queue();
      return;
    }
    List<Integer> removed =
        parseRemoveIndices(event.getValues(), session.orElseThrow().tracks().size());
    if (removed.isEmpty() && !event.getValues().isEmpty()) {
      event
          .reply("Those items are no longer available.")
          .setEphemeral(true)
          .queue(null, err -> log.warn("remove reply failed", err));
      return;
    }
    event.deferEdit().queue();
    List<ParsedTrack> remaining = new ArrayList<>(session.orElseThrow().tracks());
    for (int i = removed.size() - 1; i >= 0; i--) {
      int index = removed.get(i);
      if (index >= 0 && index < remaining.size()) {
        remaining.remove(index);
      }
    }
    if (remaining.isEmpty()) {
      sessions.remove(sessionId);
      deletePreview(event.getHook());
      event
          .getHook()
          .sendMessage(cancelledWithRemoved(removed.size()))
          .setEphemeral(true)
          .queue(null, err -> log.warn("remove reply failed", err));
      return;
    }
    ImportSession updated =
        sessions.updateTracks(sessionId, remaining).orElseThrow();
    event
        .getHook()
        .editOriginal(
            previewEdit(
                updated.id(), updated.tracks(), updated.tracks().size(), 0, false, 0))
        .queue();
    event
        .getHook()
        .sendMessage(removedReport(removed.size(), remaining.size()))
        .setEphemeral(true)
        .queue(null, err -> log.warn("remove reply failed", err));
  }

  private void onCancel(ButtonInteractionEvent event, UUID sessionId) {
    Optional<ImportSession> session = sessions.get(sessionId);
    if (session.isEmpty()) {
      event.reply("This import expired.").setEphemeral(true).queue();
      return;
    }
    if (session.orElseThrow().userId() != event.getUser().getIdLong()) {
      event.reply("Only the importer can do that.").setEphemeral(true).queue();
      return;
    }
    int discarded = session.orElseThrow().tracks().size();
    sessions.remove(sessionId);
    event.deferEdit().queue();
    deletePreview(event.getHook());
    event
        .getHook()
        .sendMessage(cancelledReport(discarded))
        .setEphemeral(true)
        .queue(null, err -> log.warn("cancel reply failed", err));
  }

  private void onPlayAll(ButtonInteractionEvent event, UUID sessionId) {
    Optional<ImportSession> session = sessions.get(sessionId);
    if (session.isEmpty()) {
      event.reply("This import expired.").setEphemeral(true).queue();
      return;
    }
    if (session.orElseThrow().userId() != event.getUser().getIdLong()) {
      event.reply("Only the importer can do that.").setEphemeral(true).queue();
      return;
    }
    if (!inFlight.add(sessionId)) {
      event.reply("This import is already being queued.").setEphemeral(true).queue();
      return;
    }
    if (event.getGuild() == null) {
      inFlight.remove(sessionId);
      event.reply("This only works in a server.").setEphemeral(true).queue();
      return;
    }
    // Blocking member/voice lookups must not stall the gateway thread.
    EXECUTOR.execute(() -> onPlayAllAsync(event, sessionId));
  }

  private void onPlayAllAsync(ButtonInteractionEvent event, UUID sessionId) {
    Optional<ImportSession> session = sessions.get(sessionId);
    if (session.isEmpty()) {
      inFlight.remove(sessionId);
      event.reply("This import expired.").setEphemeral(true).queue();
      return;
    }
    // Member may not be cached without the privileged GUILD_MEMBERS intent,
    // so resolve it from the guild (REST) when needed.
    net.dv8tion.jda.api.entities.Member member = event.getMember();
    if (member == null) {
      try {
        member = event.getGuild().retrieveMemberById(event.getUser().getId()).complete();
      } catch (Exception e) {
        log.warn("Could not resolve member for Play all", e);
      }
    }
    if (member == null) {
      inFlight.remove(sessionId);
      event.reply("Could not resolve your member. Try again.").setEphemeral(true).queue();
      return;
    }
    AudioChannelUnion channel = member.getVoiceState() != null ? member.getVoiceState().getChannel() : null;
    if (channel == null) {
      for (net.dv8tion.jda.api.entities.GuildVoiceState vs : event.getGuild().getVoiceStates()) {
        if (vs.getMember() != null && vs.getMember().getIdLong() == event.getUser().getIdLong()) {
          channel = vs.getChannel();
          break;
        }
      }
    }
    if (channel == null) {
      try {
        net.dv8tion.jda.api.entities.GuildVoiceState vs =
            event.getGuild().retrieveMemberVoiceStateById(event.getUser().getIdLong()).complete();
        channel = vs != null ? vs.getChannel() : null;
      } catch (Exception e) {
        log.warn("Could not retrieve voice state", e);
      }
    }
    if (channel == null) {
      inFlight.remove(sessionId);
      event.reply("Join a voice channel first.").setEphemeral(true).queue();
      return;
    }
    final AudioChannelUnion voiceChannel = channel;
    List<ParsedTrack> tracks = session.orElseThrow().tracks();
    // Take the session out now: a second click, Remove or Cancel during the
    // resolve reports back instead of acting twice on the same batch.
    sessions.remove(sessionId);
    long guildId = event.getGuild().getIdLong();
    try {
      event.deferEdit().queue();
    } catch (Exception e) {
      log.warn("deferEdit failed", e);
    }
    try {
      event
          .getHook()
          .editOriginal(
              new MessageEditBuilder().setContent("Resolving 0/" + tracks.size() + "…").build())
          .queue();
    } catch (Exception e) {
      log.warn("initial edit failed", e);
    }

    ConcurrentHashMap<Integer, TrackCandidate> found = new ConcurrentHashMap<>();
    AtomicInteger done = new AtomicInteger();
    Instant[] lastEdit = {Instant.now()};
    List<CompletableFuture<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < tracks.size(); i++) {
      final int index = i;
      tasks.add(
          CompletableFuture.runAsync(
              () -> {
                try {
                  resolveSlots.acquire();
                  try {
                    List<TrackCandidate> candidates =
                        playback
                            .resolve(new OcrQuery(tracks.get(index)))
                            .get(resolveTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!candidates.isEmpty()) {
                      TrackCandidate best = candidates.get(0);
                      found.put(index, best);
                      String query =
                          tracks.get(index).title() + " " + tracks.get(index).artist();
                      double score =
                          new dev.chico.bot.source.Ranker(0.0).score(best, query.strip());
                      log.info(
                          "Play all resolving {}/{} query={} -> {} score={} source={}",
                          index + 1,
                          tracks.size(),
                          query,
                          best.title(),
                          String.format("%.3f", score),
                          best.source());
                    } else {
                      log.info(
                          "Play all resolving {}/{} query={} -> not found",
                          index + 1,
                          tracks.size(),
                          tracks.get(index).title() + " " + tracks.get(index).artist());
                    }
                  } finally {
                    resolveSlots.release();
                  }
                } catch (Exception ignored) {
                  // A single track never fails the batch; it counts as not found.
                } finally {
                  int finished = done.incrementAndGet();
                  synchronized (lastEdit) {
                    if (Duration.between(lastEdit[0], Instant.now()).toMillis() >= 2000
                        && finished < tracks.size()) {
                      lastEdit[0] = Instant.now();
                      try {
                        event
                            .getHook()
                            .editOriginal(
                                new MessageEditBuilder()
                                    .setContent("Resolving " + finished + "/" + tracks.size()
                                        + "…")
                                    .build())
                            .queue();
                      } catch (Exception e) {
                        log.warn("progress edit failed", e);
                      }
                    }
                  }
                }
              },
              EXECUTOR));
    }
    CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
        .orTimeout(resolveTimeout.toMillis() * tracks.size() + 10_000,
            java.util.concurrent.TimeUnit.MILLISECONDS)
        .whenComplete(
            (ignored, failure) -> {
              inFlight.remove(sessionId);
              if (failure != null) {
                log.warn("Play all resolve failed", failure);
              }
              List<TrackCandidate> ordered = new ArrayList<>();
              for (int i = 0; i < tracks.size(); i++) {
                TrackCandidate candidate = found.get(i);
                if (candidate != null) {
                  ordered.add(candidate);
                }
              }
              int notFound = tracks.size() - ordered.size();
              int enqueued = 0;
              boolean joinFailed = false;
              try {
                if (!ordered.isEmpty()) {
                  if (!playback.connect(guildId, voiceChannel.getIdLong())) {
                    joinFailed = true;
                  } else {
                    enqueued =
                        playback.enqueueResolved(
                            guildId, ordered, event.getUser().getIdLong());
                  }
                }
              } catch (Exception e) {
                log.warn("Play all enqueue failed", e);
              }
              int didNotFit = ordered.size() - enqueued;
              String report = "Enqueued " + enqueued + ".";
              if (joinFailed) {
                report = "Could not join that voice channel.";
              } else if (notFound > 0) {
                report += " " + notFound + " not found.";
              }
              if (didNotFit > 0 && !joinFailed) {
                report += " " + didNotFit + " did not fit in the queue.";
              }
              if (failure != null && ordered.isEmpty()) {
                report = "Import failed: could not resolve any track. Try again.";
              }
              deletePreview(event.getHook());
              event
                  .getHook()
                  .sendMessage(report)
                  .setEphemeral(true)
                  .queue(null, err -> log.warn("final reply failed", err));
            });
  }

  private static net.dv8tion.jda.api.utils.messages.MessageEditData previewEdit(
      UUID sessionId,
      List<ParsedTrack> tracks,
      int total,
      int droppedImages,
      boolean warned,
      int suspiciousDropped) {
    return new MessageEditBuilder()
        .setEmbeds(
            Embeds.importPreview(
                tracks, total, droppedImages, warned, suspiciousDropped))
        .setComponents(previewComponents(sessionId, tracks))
        .setAllowedMentions(Collections.emptyList())
        .build();
  }

  private static List<net.dv8tion.jda.api.components.MessageTopLevelComponent>
      previewComponents(UUID sessionId, List<ParsedTrack> tracks) {
    return List.of(
        ActionRow.of(
            Button.primary(PLAY_PREFIX + sessionId, "Play all"),
            Button.danger(CANCEL_PREFIX + sessionId, "Cancel")),
        ActionRow.of(removeMenu(sessionId, tracks)));
  }

  /** Parses remove-menu values, ignoring forged, duplicate and out-of-range ones. */
  static List<Integer> parseRemoveIndices(List<String> values, int size) {
    java.util.Set<Integer> parsed = new java.util.TreeSet<>();
    for (String value : values) {
      try {
        int index = Integer.parseInt(value);
        if (index >= 0 && index < size) {
          parsed.add(index);
        }
      } catch (NumberFormatException ignored) {
        // Forged client value: skip it.
      }
    }
    return List.copyOf(parsed);
  }

  private static void deletePreview(
      net.dv8tion.jda.api.interactions.InteractionHook hook) {
    hook.deleteOriginal().queue(null, err -> log.debug("preview delete failed", err));
  }

  static String cancelledReport(int discarded) {
    return "Import cancelled — " + discarded + " tracks discarded, nothing queued.";
  }

  static String cancelledWithRemoved(int removed) {
    return "Removed " + removed + " items. Import cancelled — nothing queued.";
  }

  static String removedReport(int removed, int remaining) {
    return "Removed " + removed + " (" + remaining + " remaining).";
  }

  private static StringSelectMenu removeMenu(UUID sessionId, List<ParsedTrack> tracks) {
    StringSelectMenu.Builder menu =
        StringSelectMenu.create(REMOVE_PREFIX + sessionId).setPlaceholder("Remove items");
    int shown = Math.min(tracks.size(), 25);
    for (int i = 0; i < shown; i++) {
      ParsedTrack track = tracks.get(i);
      String label = (i + 1) + ". " + track.title()
          + (track.artist().isEmpty() ? "" : " - " + track.artist());
      if (label.length() > 90) {
        label = label.substring(0, 90);
      }
      menu.addOption(label, String.valueOf(i));
    }
    menu.setMinValues(1);
    menu.setMaxValues(Math.max(shown, 1));
    return menu.build();
  }

  private static UUID sessionId(String componentId, String prefix) {
    try {
      return UUID.fromString(componentId.substring(prefix.length()));
    } catch (IllegalArgumentException e) {
      return new UUID(0L, 0L);
    }
  }
}
