package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.application.PlaylistPreview;
import dev.chico.bot.application.PlaylistSession;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Playlist summary with play-all / single-track / cancel and paging. */
public final class PlaylistConfirm extends ListenerAdapter {

  private static final Logger log = LoggerFactory.getLogger(PlaylistConfirm.class);

  static final String PLAY = "playlist-play:";
  static final String SINGLE = "playlist-single:";
  static final String CANCEL = "playlist-cancel:";
  static final String PREV = "playlist-prev:";
  static final String NEXT = "playlist-next:";

  private final PlaybackService playback;

  public PlaylistConfirm(PlaybackService playback) {
    this.playback = playback;
  }

  /** Public preview message sent after `/play <playlist link>`. */
  public static MessageCreateData message(PlaylistPreview preview) {
    MessageCreateBuilder builder =
        new MessageCreateBuilder()
            .setEmbeds(
                Embeds.playlistPreview(
                    preview.items(), 0, preview.canPlaySingle(), preview.error()))
            .setAllowedMentions(Collections.emptyList());
    if (preview.sessionId() != null) {
      builder.setComponents(components(preview.sessionId(), 0, preview.canPlaySingle(), preview.items().size()));
    }
    return builder.build();
  }

  @Override
  public void onButtonInteraction(ButtonInteractionEvent event) {
    String id = event.getComponentId();
    if (!isPlaylistButton(id)) {
      return;
    }
    UUID sessionId = sessionId(id);
    int page = page(id);
    Optional<PlaylistSession> session = playback.playlistSession(sessionId);
    if (session.isEmpty()) {
      event.reply("This playlist expired.").setEphemeral(true).queue();
      return;
    }
    if (session.orElseThrow().userId() != event.getUser().getIdLong()) {
      event.reply("Only the requester can do that.").setEphemeral(true).queue();
      return;
    }
    if (id.startsWith(PREV) || id.startsWith(NEXT)) {
      int target = id.startsWith(NEXT) ? page + 1 : Math.max(0, page - 1);
      event.editMessage(edit(session.orElseThrow(), target)).queue(
          null, err -> log.warn("playlist page edit failed", err));
      return;
    }
    if (id.startsWith(CANCEL)) {
      playback.cancelPlaylist(sessionId);
      event.deferEdit().queue();
      deletePreview(event);
      event
          .getHook()
          .sendMessage("Playlist cancelled — nothing queued.")
          .setEphemeral(true)
          .queue(null, err -> log.warn("playlist cancel reply failed", err));
      return;
    }
    confirm(event, sessionId, id.startsWith(SINGLE));
  }

  private void confirm(ButtonInteractionEvent event, UUID sessionId, boolean onlySingle) {
    if (event.getGuild() == null) {
      event.reply("This only works in a server.").setEphemeral(true).queue();
      return;
    }
    AudioChannelUnion channel = voiceChannel(event);
    if (channel == null) {
      event.reply("Join a voice channel first.").setEphemeral(true).queue();
      return;
    }
    event.deferEdit().queue();
    playback
        .confirmPlaylist(
            sessionId,
            event.getUser().getIdLong(),
            event.getGuild().getIdLong(),
            onlySingle,
            channel.getIdLong())
        .thenAccept(
            report -> {
              deletePreview(event);
              event
                  .getHook()
                  .sendMessage(report)
                  .setEphemeral(true)
                  .queue(null, err -> log.warn("playlist report failed", err));
            })
        .exceptionally(
            failure -> {
              log.warn("playlist confirm failed", failure);
              event
                  .getHook()
                  .sendMessage("Playlist failed. Try again.")
                  .setEphemeral(true)
                  .queue(null, err -> log.warn("playlist report failed", err));
              return null;
            });
  }

  private static AudioChannelUnion voiceChannel(ButtonInteractionEvent event) {
    Member member = event.getMember();
    if (member == null) {
      try {
        member = event.getGuild().retrieveMemberById(event.getUser().getId()).complete();
      } catch (Exception e) {
        log.warn("Could not resolve member for playlist confirm", e);
      }
    }
    if (member == null) {
      return null;
    }
    var state = member.getVoiceState();
    if (state != null) {
      return state.getChannel();
    }
    try {
      var fetched =
          event
              .getGuild()
              .retrieveMemberVoiceStateById(event.getUser().getId())
              .complete();
      return fetched == null ? null : fetched.getChannel();
    } catch (Exception e) {
      log.warn("Could not retrieve voice state for playlist confirm", e);
      return null;
    }
  }

  private static void deletePreview(ButtonInteractionEvent event) {
    event.getHook().deleteOriginal().queue(null, err -> log.debug("preview delete failed", err));
  }

  private static boolean isPlaylistButton(String id) {
    return id.startsWith(PLAY)
        || id.startsWith(SINGLE)
        || id.startsWith(CANCEL)
        || id.startsWith(PREV)
        || id.startsWith(NEXT);
  }

  private static MessageEditData edit(PlaylistSession session, int page) {
    return new MessageEditBuilder()
        .setEmbeds(
            Embeds.playlistPreview(
                session.items(), page, session.singleTrackUri() != null, null))
        .setComponents(components(session.id(), page, session.singleTrackUri() != null, session.items().size()))
        .setAllowedMentions(Collections.emptyList())
        .build();
  }

  private static List<net.dv8tion.jda.api.components.MessageTopLevelComponent> components(
      UUID sessionId, int page, boolean canPlaySingle, int itemCount) {
    List<Button> firstRow = new java.util.ArrayList<>();
    firstRow.add(
        Button.primary(PLAY + sessionId, "Play playlist")
            .withDisabled(itemCount == 0));
    if (canPlaySingle) {
      firstRow.add(Button.secondary(SINGLE + sessionId, "Only the linked track"));
    }
    firstRow.add(Button.danger(CANCEL + sessionId, "Cancel"));
    int totalPages = Math.max(1, (itemCount + 9) / 10);
    List<net.dv8tion.jda.api.components.MessageTopLevelComponent> rows =
        new java.util.ArrayList<>();
    rows.add(ActionRow.of(firstRow));
    if (totalPages > 1) {
      rows.add(
          ActionRow.of(
              Button.secondary(PREV + sessionId + ":" + page, "Previous")
                  .withDisabled(page <= 0),
              Button.secondary(NEXT + sessionId + ":" + page, "Next")
                  .withDisabled(page >= totalPages - 1)));
    }
    return rows;
  }

  private static UUID sessionId(String componentId) {
    String withoutPrefix = componentId.substring(componentId.indexOf(':') + 1);
    int colon = withoutPrefix.indexOf(':');
    String raw = colon < 0 ? withoutPrefix : withoutPrefix.substring(0, colon);
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      return new UUID(0L, 0L);
    }
  }

  private static int page(String componentId) {
    int last = componentId.lastIndexOf(':');
    if (last <= componentId.indexOf(':')
        || componentId.startsWith(PLAY)
        || componentId.startsWith(SINGLE)
        || componentId.startsWith(CANCEL)) {
      return 0;
    }
    try {
      return Integer.parseInt(componentId.substring(last + 1));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
