package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.source.TrackCandidate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One live queue message per guild. `/queue` creates it; every playback
 * mutation and track change edits it in place. Memory only: a restart loses
 * the message id and the next `/queue` recreates it.
 */
public final class QueueLive {

  private static final Logger log = LoggerFactory.getLogger(QueueLive.class);

  /** Channel/message pair of the live queue. */
  public record Entry(long channelId, long messageId) {
  }

  /** Pure store, separated so ordering is testable without JDA. */
  public static final class Store {
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public synchronized Entry replace(long guildId, Entry next) {
      return entries.put(guildId, next);
    }

    public synchronized Optional<Entry> get(long guildId) {
      return Optional.ofNullable(entries.get(guildId));
    }

    public synchronized void remove(long guildId) {
      entries.remove(guildId);
    }
  }

  private final JDA jda;
  private final PlaybackService playback;
  private final Store store = new Store();
  private final Map<Long, CompletableFuture<Void>> postChains = new ConcurrentHashMap<>();

  public QueueLive(JDA jda, PlaybackService playback) {
    this.jda = jda;
    this.playback = playback;
  }

  /** Deletes the previous live and posts a fresh one, deleting before send. */
  public void post(long guildId, MessageChannel channel) {
    postChains.compute(
        guildId,
        (id, previous) -> {
          CompletableFuture<Void> base =
              previous == null ? CompletableFuture.completedFuture(null) : previous;
          return base.thenCompose(
              ignored -> {
                store
                    .get(guildId)
                    .ifPresent(old -> deleteQuietly(old));
                store.remove(guildId);
                CompletableFuture<Void> sent = new CompletableFuture<>();
                channel
                    .sendMessageEmbeds(embed(guildId))
                    .setAllowedMentions(Collections.emptyList())
                    .queue(
                        message -> {
                          store.replace(
                              guildId,
                              new Entry(channel.getIdLong(), message.getIdLong()));
                          sent.complete(null);
                        },
                        failure -> {
                          log.warn("live queue post failed", failure);
                          sent.complete(null);
                        });
                return sent;
              });
        });
  }

  /** Edits the stored live message, if any. Never throws to the caller. */
  public void refreshIfPresent(long guildId) {
    store
        .get(guildId)
        .ifPresent(
            entry -> {
              MessageChannel channel =
                  jda.getChannelById(MessageChannel.class, entry.channelId());
              if (channel == null) {
                store.remove(guildId);
                return;
              }
              channel
                  .editMessageById(
                      entry.messageId(),
                      new MessageEditBuilder()
                          .setEmbeds(embed(guildId))
                          .setAllowedMentions(Collections.emptyList())
                          .build())
                  .queue(null, failure -> onEditFailure(guildId, failure));
            });
  }

  private void onEditFailure(long guildId, Throwable failure) {
    if (failure instanceof ErrorResponseException error) {
      ErrorResponse response = error.getErrorResponse();
      if (response == ErrorResponse.UNKNOWN_MESSAGE
          || response == ErrorResponse.UNKNOWN_CHANNEL) {
        store.remove(guildId);
        return;
      }
    }
    log.debug("live queue edit failed", failure);
  }

  private void deleteQuietly(Entry entry) {
    MessageChannel channel = jda.getChannelById(MessageChannel.class, entry.channelId());
    if (channel == null) {
      return;
    }
    channel
        .deleteMessageById(entry.messageId())
        .queue(null, failure -> log.debug("live queue delete failed", failure));
  }

  private net.dv8tion.jda.api.entities.MessageEmbed embed(long guildId) {
    PlaybackService.QueueView view = playback.queueView(guildId);
    List<TrackCandidate> all = new ArrayList<>();
    view.current().ifPresent(all::add);
    all.addAll(view.queued());
    return Embeds.queue(all, playback.loopView(guildId));
  }
}
