package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.application.PlaybackService.PlayResult;
import dev.chico.bot.application.PlaybackService.QueueView;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Music commands. Playback stays in the application layer; this formats output. */
public final class MusicCommands extends ListenerAdapter {

  static final String PLAY = "play";
  static final String PAUSE = "pause";
  static final String RESUME = "resume";
  static final String SKIP = "skip";
  static final String STOP = "stop";
  static final String QUEUE = "queue";
  static final String REMOVE = "remove";
  static final String LEAVE = "leave";
  static final String NOW_PLAYING = "nowplaying";
  static final String LOOP = "loop";

  private static final Logger log = LoggerFactory.getLogger(MusicCommands.class);

  private final PlaybackService playback;
  private final QueueLive liveQueue;

  public MusicCommands(PlaybackService playback, QueueLive liveQueue) {
    this.playback = playback;
    this.liveQueue = liveQueue;
  }

  /** One list, registered per guild in dev and globally otherwise. */
  private static List<net.dv8tion.jda.api.interactions.commands.build.CommandData> commandData() {
    return List.of(
        Commands.slash(PLAY, "Play a song by name or link")
            .addOption(OptionType.STRING, "query", "Song name or audio URL", false),
        Commands.slash(PAUSE, "Pause the current track"),
        Commands.slash(RESUME, "Resume the current track"),
        Commands.slash(SKIP, "Skip the current track or jump to a queued one")
            .addOption(OptionType.STRING, "target", "Queue number or name", false),
        Commands.slash(STOP, "Stop and clear the queue"),
        Commands.slash(QUEUE, "Show the queue"),
        Commands.slash(REMOVE, "Remove a queued track")
            .addOption(OptionType.STRING, "target", "Queue number or name", true),
        Commands.slash(LEAVE, "Leave the voice channel"),
        Commands.slash(NOW_PLAYING, "Show the current track"),
        Commands.slash(LOOP, "Loop the current track, a queued position or the queue")
            .addOption(
                OptionType.STRING, "target", "Queue number, all, or disable", false));
  }

  static void register(Guild guild) {
    for (var command : commandData()) {
      guild.upsertCommand(command).queue();
    }
  }

  static void registerGlobal(net.dv8tion.jda.api.JDA jda) {
    for (var command : commandData()) {
      jda.upsertCommand(command).queue();
    }
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    switch (event.getName()) {
      case PLAY -> onPlay(event);
      case PAUSE -> reply(event, playback.pause(guildOf(event)));
      case RESUME -> reply(event, playback.resume(guildOf(event)));
      case SKIP -> onSkip(event);
      case STOP -> reply(event, playback.stop(guildOf(event)));
      case QUEUE -> onQueue(event);
      case REMOVE -> onRemove(event);
      case LEAVE -> reply(event, playback.leave(guildOf(event)));
      case NOW_PLAYING -> onNowPlaying(event);
      case LOOP -> onLoop(event);
      default -> {
      }
    }
  }

  private void onPlay(SlashCommandInteractionEvent event) {
    if (event.getGuild() == null) {
      event.reply("This only works in a server.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    if (event.getMember() == null) {
      event.reply("Could not resolve your member. Try again.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    OptionMapping option = event.getOption("query");
    if (option == null || option.getAsString().isBlank()) {
      long guildId = event.getGuild().getIdLong();
      if (playback.isPaused(guildId)) {
        // Reuse resume: reply() keeps the same-channel guard.
        reply(event, playback.resume(guildId));
        return;
      }
      event.reply("Tell me what to play: `/play <song name or link>`.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    var voiceState = event.getMember().getVoiceState();
    AudioChannelUnion channel = voiceState != null ? voiceState.getChannel() : null;
    if (channel == null) {
      event.reply("Join a voice channel first.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    var self = event.getGuild().getSelfMember();
    if (!self.hasPermission(
        channel,
        net.dv8tion.jda.api.Permission.VOICE_CONNECT,
        net.dv8tion.jda.api.Permission.VOICE_SPEAK)) {
      event.reply("I don't have permission to join that voice channel.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    event.deferReply().queue(null, err -> log.warn("deferReply failed", err));
    long guildId = event.getGuild().getIdLong();
    long userId = event.getMember().getIdLong();
    playback
        .play(guildId, channel.getIdLong(), userId, option.getAsString())
        .thenAccept(
            result -> {
              if (result.playlist() != null) {
                event
                    .getHook()
                    .sendMessage(PlaylistConfirm.message(result.playlist()))
                    .queue(null, err -> log.warn("play reply failed", err));
                return;
              }
              if (result.queued()) {
                event.getHook().sendMessageEmbeds(Embeds.nowPlaying(result.candidate())).queue(
                    null, err -> log.warn("play reply failed", err));
              } else {
                event.getHook().sendMessageEmbeds(Embeds.error(result.message())).queue(
                    null, err -> log.warn("play reply failed", err));
              }
            });
  }

  private void onSkip(SlashCommandInteractionEvent event) {
    if (!inSameChannel(event)) {
      return;
    }
    OptionMapping option = event.getOption("target");
    Optional<String> target =
        option == null ? Optional.empty() : Optional.of(option.getAsString());
    reply(
        event,
        playback.skip(
            guildOf(event),
            target,
            event.getUser().getIdLong(),
            voiceHumans(event.getGuild())));
  }

  private void onRemove(SlashCommandInteractionEvent event) {
    if (!inSameChannel(event)) {
      return;
    }
    OptionMapping option = event.getOption("target");
    if (option == null) {
      return;
    }
    reply(
        event,
        playback.remove(
            guildOf(event),
            option.getAsString(),
            event.getUser().getIdLong(),
            voiceHumans(event.getGuild())));
  }

  /** Non-bot humans in the bot's current voice channel (vote denominator). */
  private static java.util.Set<Long> voiceHumans(Guild guild) {
    var channel = guild.getAudioManager().getConnectedChannel();
    if (channel == null) {
      return java.util.Set.of();
    }
    return channel.getMembers().stream()
        .filter(member -> !member.getUser().isBot())
        .map(net.dv8tion.jda.api.entities.Member::getIdLong)
        .collect(java.util.stream.Collectors.toSet());
  }

  private void onLoop(SlashCommandInteractionEvent event) {
    if (!inSameChannel(event)) {
      return;
    }
    OptionMapping option = event.getOption("target");
    Optional<String> target =
        option == null ? Optional.empty() : Optional.of(option.getAsString());
    reply(event, playback.loop(guildOf(event), target));
  }

  private void onQueue(SlashCommandInteractionEvent event) {
    if (event.getGuild() == null) {
      event.reply("This only works in a server.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    event.deferReply(true).queue(null, err -> log.warn("deferReply failed", err));
    liveQueue.post(event.getGuild().getIdLong(), event.getChannel());
    event
        .getHook()
        .sendMessage("Live queue posted below.")
        .setEphemeral(true)
        .queue(null, err -> log.warn("queue reply failed", err));
  }

  private void onNowPlaying(SlashCommandInteractionEvent event) {
    if (event.getGuild() == null) {
      event.reply("This only works in a server.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    event.deferReply().queue(null, err -> log.warn("deferReply failed", err));
    QueueView view = playback.queueView(guildOf(event));
    if (view.current().isEmpty()) {
      event.getHook().sendMessageEmbeds(Embeds.error("Nothing is playing.")).queue(
          null, err -> log.warn("nowplaying reply failed", err));
      return;
    }
    event.getHook().sendMessageEmbeds(Embeds.nowPlaying(view.current().orElseThrow())).queue(
        null, err -> log.warn("nowplaying reply failed", err));
  }

  private void reply(SlashCommandInteractionEvent event, String message) {
    if (event.getMember() == null || event.getGuild() == null) {
      event.reply("This only works in a server.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    if (isConnectedElsewhere(event)) {
      event.reply("Join my voice channel to control playback.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return;
    }
    event.reply(message).queue(null, err -> log.warn("reply failed", err));
  }

  private boolean inSameChannel(SlashCommandInteractionEvent event) {
    if (event.getMember() == null || event.getGuild() == null) {
      return false;
    }
    if (isConnectedElsewhere(event)) {
      event.reply("Join my voice channel to control playback.").setEphemeral(true).queue(null, err -> log.warn("reply failed", err));
      return false;
    }
    return true;
  }

  private boolean isConnectedElsewhere(SlashCommandInteractionEvent event) {
    if (event.getMember() == null || event.getGuild() == null) {
      return true;
    }
    var selfChannel = event.getGuild().getAudioManager().getConnectedChannel();
    if (selfChannel == null) {
      return false;
    }
    var voiceState = event.getMember().getVoiceState();
    var memberChannel = voiceState != null ? voiceState.getChannel() : null;
    return memberChannel == null || memberChannel.getIdLong() != selfChannel.getIdLong();
  }

  private long guildOf(SlashCommandInteractionEvent event) {
    return event.getGuild().getIdLong();
  }
}
