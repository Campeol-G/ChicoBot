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

  static final String PLAY_PREFIX = "import-play:";
  static final String CANCEL_PREFIX = "import-cancel:";
  static final String REMOVE_PREFIX = "import-remove:";

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private final ImportSession.Store sessions;
  private final PlaybackService playback;
  private final Semaphore resolveSlots;
  private final Duration resolveTimeout;

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
    return previewEdit(sessionId, tracks, totalDetected);
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
    event.deferEdit().queue();
    List<Integer> removed =
        event.getValues().stream().map(Integer::parseInt).sorted().toList();
    List<ParsedTrack> remaining = new ArrayList<>(session.orElseThrow().tracks());
    for (int i = removed.size() - 1; i >= 0; i--) {
      int index = removed.get(i);
      if (index >= 0 && index < remaining.size()) {
        remaining.remove(index);
      }
    }
    if (remaining.isEmpty()) {
      sessions.remove(sessionId);
      event
          .getHook()
          .editOriginal(
              new MessageEditBuilder().setContent("Import cancelled, no tracks left.").build())
          .queue();
      return;
    }
    ImportSession updated =
        sessions.updateTracks(sessionId, remaining).orElseThrow();
    event
        .getHook()
        .editOriginal(
            previewEdit(updated.id(), updated.tracks(), updated.tracks().size()))
        .queue();
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
    sessions.remove(sessionId);
    event.deferEdit().queue();
    event
        .getHook()
        .editOriginal(new MessageEditBuilder().setContent("Import cancelled.").build())
        .queue();
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
    if (event.getMember() == null || event.getGuild() == null) {
      return;
    }
    AudioChannelUnion channel = event.getMember().getVoiceState().getChannel();
    if (channel == null) {
      event.reply("Join a voice channel first.").setEphemeral(true).queue();
      return;
    }
    List<ParsedTrack> tracks = session.orElseThrow().tracks();
    long guildId = event.getGuild().getIdLong();
    event.deferEdit().queue();
    event
        .getHook()
        .editOriginal(
            new MessageEditBuilder().setContent("Resolving 0/" + tracks.size() + "…").build())
        .queue();

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
                        playback.resolve(new OcrQuery(tracks.get(index))).get();
                    if (!candidates.isEmpty()) {
                      found.put(index, candidates.get(0));
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
                      event
                          .getHook()
                          .editOriginal(
                              new MessageEditBuilder()
                                  .setContent("Resolving " + finished + "/" + tracks.size()
                                      + "…")
                                  .build())
                          .queue();
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
              List<TrackCandidate> ordered = new ArrayList<>();
              for (int i = 0; i < tracks.size(); i++) {
                TrackCandidate candidate = found.get(i);
                if (candidate != null) {
                  ordered.add(candidate);
                }
              }
              int notFound = tracks.size() - ordered.size();
              int enqueued = 0;
              if (!ordered.isEmpty()) {
                playback.connect(guildId, channel.getIdLong());
                enqueued = playback.enqueueResolved(guildId, ordered);
              }
              int didNotFit = ordered.size() - enqueued;
              sessions.remove(sessionId);
              String report = "Enqueued " + enqueued + ".";
              if (notFound > 0) {
                report += " " + notFound + " not found.";
              }
              if (didNotFit > 0) {
                report += " " + didNotFit + " did not fit in the queue.";
              }
              event.getHook().editOriginal(new MessageEditBuilder().setContent(report).build())
                  .queue();
            });
  }

  private static net.dv8tion.jda.api.utils.messages.MessageEditData previewEdit(
      UUID sessionId, List<ParsedTrack> tracks, int total) {
    return new MessageEditBuilder()
        .setEmbeds(Embeds.importPreview(tracks, total))
        .setComponents(
            ActionRow.of(
                Button.primary(PLAY_PREFIX + sessionId, "Play all"),
                Button.danger(CANCEL_PREFIX + sessionId, "Cancel")),
            ActionRow.of(removeMenu(sessionId, tracks)))
        .setAllowedMentions(Collections.emptyList())
        .build();
  }

  private static StringSelectMenu removeMenu(UUID sessionId, List<ParsedTrack> tracks) {
    StringSelectMenu.Builder menu =
        StringSelectMenu.create(REMOVE_PREFIX + sessionId).setPlaceholder("Remove items");
    for (int i = 0; i < tracks.size(); i++) {
      ParsedTrack track = tracks.get(i);
      String label = (i + 1) + ". " + track.title()
          + (track.artist().isEmpty() ? "" : " - " + track.artist());
      if (label.length() > 90) {
        label = label.substring(0, 90);
      }
      menu.addOption(label, String.valueOf(i));
    }
    menu.setMinValues(1);
    menu.setMaxValues(tracks.size());
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
