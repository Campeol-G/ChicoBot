package dev.chico.bot.discord;

import dev.chico.bot.application.AttachmentInput;
import dev.chico.bot.application.ImportFromImage;
import dev.chico.bot.config.Config;
import dev.chico.bot.security.RateLimiter;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/** "Play music from image" message command for older image messages. */
public final class ImageContextMenu extends ListenerAdapter {

  static final String COMMAND = "Play music from image";

  private final ImportFromImage importFlow;
  private final RateLimiter rateLimiter = new RateLimiter();
  private final Clock clock = Clock.systemUTC();
  private final int cooldownUserS;

  public ImageContextMenu(ImportFromImage importFlow, Config config) {
    this.importFlow = importFlow;
    this.cooldownUserS = config.cooldownUserS();
  }

  static void register(Guild guild) {
    guild.upsertCommand(Commands.message(COMMAND)).queue();
  }

  static void registerGlobal(JDA jda) {
    jda.upsertCommand(Commands.message(COMMAND)).queue();
  }

  @Override
  public void onMessageContextInteraction(MessageContextInteractionEvent event) {
    if (!event.getName().equals(COMMAND) || event.getGuild() == null) {
      return;
    }
    event.deferReply(true).queue();
    long userId = event.getUser().getIdLong();
    if (!rateLimiter.tryAcquire("import:" + userId, cooldownUserS, clock)) {
      event
          .getHook()
          .sendMessage("Slow down. Wait a few seconds before another import.")
          .setEphemeral(true)
          .queue();
      return;
    }
    List<AttachmentInput> inputs = new ArrayList<>();
    for (Message.Attachment attachment
        : event.getTarget().getAttachments().stream()
            .filter(Message.Attachment::isImage)
            .limit(5)
            .toList()) {
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
    if (inputs.isEmpty()) {
      event
          .getHook()
          .sendMessage("That message has no image. Run this on a playlist screenshot.")
          .setEphemeral(true)
          .queue();
      return;
    }
    importFlow
        .run(userId, event.getGuild().getIdLong(), inputs)
        .thenAccept(
            result ->
                event
                    .getHook()
                    .sendMessage(
                        PreviewButtons.previewCreate(
                            result.sessionId(), result.tracks(), result.totalDetected()))
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
              event
                  .getHook()
                  .sendMessage(
                      new MessageCreateBuilder()
                          .setContent(text)
                          .setAllowedMentions(Collections.emptyList())
                          .build())
                  .queue();
              return null;
            });
  }
}
