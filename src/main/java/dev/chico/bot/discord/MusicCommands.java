package dev.chico.bot.discord;

import dev.chico.bot.application.PlaybackService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

/** Plays audio from a name or URL through the resolver. */
public final class MusicCommands extends ListenerAdapter {

  static final String PLAY = "play";

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
  }

  static void registerGlobal(net.dv8tion.jda.api.JDA jda) {
    jda
        .upsertCommand(
            Commands.slash(PLAY, "Play a song by name or link")
                .addOption(OptionType.STRING, "query", "Song name or audio URL", true))
        .queue();
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    if (!PLAY.equals(event.getName())) {
      return;
    }
    OptionMapping option = event.getOption("query");
    if (option == null || event.getMember() == null || event.getGuild() == null) {
      event.reply("Usage: /play <song name or link> in a server.").setEphemeral(true).queue();
      return;
    }
    AudioChannelUnion channel = event.getMember().getVoiceState().getChannel();
    if (channel == null) {
      event.reply("Join a voice channel first.").setEphemeral(true).queue();
      return;
    }
    event.deferReply().queue();
    String input = option.getAsString();
    long guildId = event.getGuild().getIdLong();
    playback
        .play(guildId, channel.getIdLong(), input)
        .thenAccept(message -> event.getHook().sendMessage(message).queue());
  }
}
