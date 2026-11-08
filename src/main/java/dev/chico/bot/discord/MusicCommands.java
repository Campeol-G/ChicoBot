package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import dev.chico.bot.application.PlaybackService.PlayResult;
import dev.chico.bot.application.PlaybackService.QueueView;
import java.util.Optional;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

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

  private final PlaybackService playback;

  public MusicCommands(PlaybackService playback) {
    this.playback = playback;
  }

  static void register(Guild guild) {
    guild
        .upsertCommand(
            Commands.slash(PLAY, "Play a song by name or link")
                .addOption(OptionType.STRING, "query", "Song name or audio URL", true))
        .queue();
    guild.upsertCommand(Commands.slash(PAUSE, "Pause the current track")).queue();
    guild.upsertCommand(Commands.slash(RESUME, "Resume the current track")).queue();
    guild
        .upsertCommand(
            Commands.slash(SKIP, "Skip the current track or jump to a queued one")
                .addOption(OptionType.STRING, "target", "Queue number or name", false))
        .queue();
    guild.upsertCommand(Commands.slash(STOP, "Stop and clear the queue")).queue();
    guild.upsertCommand(Commands.slash(QUEUE, "Show the queue")).queue();
    guild
        .upsertCommand(
            Commands.slash(REMOVE, "Remove a queued track")
                .addOption(OptionType.STRING, "target", "Queue number or name", true))
        .queue();
    guild.upsertCommand(Commands.slash(LEAVE, "Leave the voice channel")).queue();
    guild.upsertCommand(Commands.slash(NOW_PLAYING, "Show the current track")).queue();
  }

  static void registerGlobal(net.dv8tion.jda.api.JDA jda) {
    jda
        .upsertCommand(
            Commands.slash(PLAY, "Play a song by name or link")
                .addOption(OptionType.STRING, "query", "Song name or audio URL", true))
        .queue();
    jda.upsertCommand(Commands.slash(PAUSE, "Pause the current track")).queue();
    jda.upsertCommand(Commands.slash(RESUME, "Resume the current track")).queue();
    jda
        .upsertCommand(
            Commands.slash(SKIP, "Skip the current track or jump to a queued one")
                .addOption(OptionType.STRING, "target", "Queue number or name", false))
        .queue();
    jda.upsertCommand(Commands.slash(STOP, "Stop and clear the queue")).queue();
    jda.upsertCommand(Commands.slash(QUEUE, "Show the queue")).queue();
    jda
        .upsertCommand(
            Commands.slash(REMOVE, "Remove a queued track")
                .addOption(OptionType.STRING, "target", "Queue number or name", true))
        .queue();
    jda.upsertCommand(Commands.slash(LEAVE, "Leave the voice channel")).queue();
    jda.upsertCommand(Commands.slash(NOW_PLAYING, "Show the current track")).queue();
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
      default -> {
      }
    }
  }

  private void onPlay(SlashCommandInteractionEvent event) {
    if (event.getMember() == null || event.getGuild() == null) {
      return;
    }
    OptionMapping option = event.getOption("query");
    if (option == null) {
      return;
    }
    AudioChannelUnion channel = event.getMember().getVoiceState().getChannel();
    if (channel == null) {
      event.reply("Join a voice channel first.").setEphemeral(true).queue();
      return;
    }
    var self = event.getGuild().getSelfMember();
    if (!self.hasPermission(
        channel,
        net.dv8tion.jda.api.Permission.VOICE_CONNECT,
        net.dv8tion.jda.api.Permission.VOICE_SPEAK)) {
      event.reply("I don't have permission to join that voice channel.").setEphemeral(true).queue();
      return;
    }
    event.deferReply().queue();
    long guildId = event.getGuild().getIdLong();
    long userId = event.getMember().getIdLong();
    playback
        .play(guildId, channel.getIdLong(), userId, option.getAsString())
        .thenAccept(
            result -> {
              if (result.queued()) {
                event.getHook().sendMessageEmbeds(Embeds.nowPlaying(result.candidate())).queue();
              } else {
                event.getHook().sendMessageEmbeds(Embeds.error(result.message())).queue();
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
    reply(event, playback.skip(guildOf(event), target));
  }

  private void onRemove(SlashCommandInteractionEvent event) {
    if (!inSameChannel(event)) {
      return;
    }
    OptionMapping option = event.getOption("target");
    if (option == null) {
      return;
    }
    reply(event, playback.remove(guildOf(event), option.getAsString()));
  }

  private void onQueue(SlashCommandInteractionEvent event) {
    event.deferReply().queue();
    QueueView view = playback.queueView(guildOf(event));
    if (view.current().isEmpty() && view.queued().isEmpty()) {
      event.getHook().sendMessageEmbeds(Embeds.error("The queue is empty.")).queue();
      return;
    }
    java.util.List<dev.chico.bot.source.TrackCandidate> all = new java.util.ArrayList<>();
    view.current().ifPresent(all::add);
    all.addAll(view.queued());
    event.getHook().sendMessageEmbeds(Embeds.queue(all)).queue();
  }

  private void onNowPlaying(SlashCommandInteractionEvent event) {
    event.deferReply().queue();
    QueueView view = playback.queueView(guildOf(event));
    if (view.current().isEmpty()) {
      event.getHook().sendMessageEmbeds(Embeds.error("Nothing is playing.")).queue();
      return;
    }
    event.getHook().sendMessageEmbeds(Embeds.nowPlaying(view.current().orElseThrow())).queue();
  }

  private void reply(SlashCommandInteractionEvent event, String message) {
    if (event.getMember() == null || event.getGuild() == null) {
      return;
    }
    if (isConnectedElsewhere(event)) {
      event.reply("Join my voice channel to control playback.").setEphemeral(true).queue();
      return;
    }
    event.reply(message).queue();
  }

  private boolean inSameChannel(SlashCommandInteractionEvent event) {
    if (event.getMember() == null || event.getGuild() == null) {
      return false;
    }
    if (isConnectedElsewhere(event)) {
      event.reply("Join my voice channel to control playback.").setEphemeral(true).queue();
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
    var memberChannel = event.getMember().getVoiceState().getChannel();
    return memberChannel == null || memberChannel.getIdLong() != selfChannel.getIdLong();
  }

  private long guildOf(SlashCommandInteractionEvent event) {
    return event.getGuild().getIdLong();
  }
}
