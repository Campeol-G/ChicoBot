package dev.chico.bot.discord;

import dev.chico.bot.application.AttachmentInput;
import dev.chico.bot.application.ImportFromImage;
import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.config.Config;
import dev.chico.bot.security.RateLimiter;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles `@bot p <query>` and `@bot` + image imports. Ignores bots and DMs. */
public final class MessageListener extends ListenerAdapter {

  private static final Logger log = LoggerFactory.getLogger(MessageListener.class);

  private final PlaybackService playback;
  private final ImportFromImage importFlow;
  private final RateLimiter rateLimiter = new RateLimiter();
  private final Clock clock = Clock.systemUTC();
  private final int cooldownUserS;

  public MessageListener(PlaybackService playback, ImportFromImage importFlow, Config config) {
    this.playback = playback;
    this.importFlow = importFlow;
    this.cooldownUserS = config.cooldownUserS();
  }

  @Override
  public void onMessageReceived(MessageReceivedEvent event) {
    if (event.getAuthor().isBot() || !event.isFromGuild() || event.getMember() == null) {
      return;
    }
    Message message = event.getMessage();
    if (!message.getMentions().isMentioned(event.getJDA().getSelfUser())) {
      return;
    }
    String text =
        message
            .getContentRaw()
            .replaceAll("<@!?" + event.getJDA().getSelfUser().getId() + ">", "")
            .strip();
    List<Message.Attachment> images =
        message.getAttachments().stream().filter(Message.Attachment::isImage).toList();
    if (text.equals("p") || text.startsWith("p ")) {
      onPlayQuery(event, text.length() > 1 ? text.substring(2).strip() : "");
    } else if (!images.isEmpty()) {
      onImport(event, images);
    } else if (!text.isEmpty()) {
      event
          .getMessage()
          .reply("Mention me with `p <song>` or with a playlist screenshot.")
          .setAllowedMentions(Collections.emptyList())
          .queue();
    }
  }

  private void onPlayQuery(MessageReceivedEvent event, String query) {
    if (query.isEmpty()) {
      event
          .getMessage()
          .reply("Tell me what to play: `@bot p <song name or link>`.")
          .setAllowedMentions(Collections.emptyList())
          .queue();
      return;
    }
    AudioChannelUnion channel = event.getMember().getVoiceState().getChannel();
    if (channel == null) {
      event
          .getMessage()
          .reply("Join a voice channel first.")
          .setAllowedMentions(Collections.emptyList())
          .queue();
      return;
    }
    long guildId = event.getGuild().getIdLong();
    long userId = event.getAuthor().getIdLong();
    playback
        .play(guildId, channel.getIdLong(), userId, query)
        .thenAccept(
            result -> {
              if (result.queued()) {
                event
                    .getMessage()
                    .replyEmbeds(Embeds.nowPlaying(result.candidate()))
                    .setAllowedMentions(Collections.emptyList())
                    .queue();
              } else {
                event
                    .getMessage()
                    .replyEmbeds(Embeds.error(result.message()))
                    .setAllowedMentions(Collections.emptyList())
                    .queue();
              }
            });
  }

  private void onImport(MessageReceivedEvent event, List<Message.Attachment> images) {
    long userId = event.getAuthor().getIdLong();
    if (!rateLimiter.tryAcquire("import:" + userId, cooldownUserS, clock)) {
      event
          .getMessage()
          .reply("Slow down. Wait a few seconds before another import.")
          .setAllowedMentions(Collections.emptyList())
          .queue();
      return;
    }
    List<AttachmentInput> inputs = new ArrayList<>();
    for (Message.Attachment attachment : images.stream().limit(5).toList()) {
      URI uri;
      try {
        uri = new URI(attachment.getUrl());
      } catch (Exception e) {
        continue;
      }
      inputs.add(
          new AttachmentInput(
              uri,
              attachment.getContentType(),
              attachment.getSize(),
              attachment.getWidth(),
              attachment.getHeight()));
    }
    for (AttachmentInput input : inputs) {
      log.debug(
          "import request contentType={} size={} dims={}x{}",
          input.contentType(), input.size(), input.width(), input.height());
    }
    if (inputs.isEmpty()) {
      event
          .getMessage()
          .reply("Could not read those attachments. Send a PNG or JPEG.")
          .setAllowedMentions(Collections.emptyList())
          .queue();
      return;
    }
    event
        .getMessage()
        .reply("Reading the image…")
        .setAllowedMentions(Collections.emptyList())
        .queue(
            progress ->
                importFlow
                    .run(userId, event.getGuild().getIdLong(), inputs)
                    .thenAccept(
                        result ->
                            progress
                                .editMessage(
                                    PreviewButtons.previewMessage(
                                        result.sessionId(), result.tracks(),
                                        result.totalDetected()))
                                .queue())
                    .exceptionally(
                        failure -> {
                          Throwable cause =
                              failure instanceof java.util.concurrent.CompletionException
                                  ? failure.getCause()
                                  : failure;
                          String text =
                              cause instanceof ImportFromImage.ImportException
                                  ? cause.getMessage()
                                  : "Import failed. Try again later.";
                          progress
                              .editMessage(
                                  new MessageEditBuilder()
                                      .setContent(text)
                                      .setAllowedMentions(Collections.emptyList())
                                      .build())
                              .queue();
                          return null;
                        }));
  }
}
