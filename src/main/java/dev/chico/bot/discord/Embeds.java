package dev.chico.bot.discord;

import dev.chico.bot.security.TextSanitizer;
import dev.chico.bot.source.Source;
import dev.chico.bot.source.TrackCandidate;
import java.awt.Color;
import java.time.Duration;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/** Discord embeds. Every external value is escaped here. */
public final class Embeds {

  private static final int MAX_QUEUE_LINES = 30;

  private Embeds() {
  }

  public static MessageEmbed nowPlaying(TrackCandidate candidate) {
    String title = TextSanitizer.escape(candidate.title());
    EmbedBuilder builder = new EmbedBuilder();
    if (candidate.uri() != null) {
      builder.setTitle(title, candidate.uri().toString());
    } else {
      builder.setTitle(title);
    }
    builder.setAuthor(TextSanitizer.escape(candidate.artist()));
    builder.setFooter(sourceName(candidate.source()) + " • " + format(candidate.duration()));
    builder.setColor(Color.GREEN);
    return builder.build();
  }

  public static MessageEmbed addedToQueue(TrackCandidate candidate, int position) {
    String title = TextSanitizer.escape(candidate.title());
    EmbedBuilder builder = new EmbedBuilder();
    if (candidate.uri() != null) {
      builder.setTitle(title, candidate.uri().toString());
    } else {
      builder.setTitle(title);
    }
    builder.setAuthor(TextSanitizer.escape(candidate.artist()));
    builder.setDescription("Added to queue at position " + position + ".");
    builder.setColor(Color.BLUE);
    return builder.build();
  }

  public static MessageEmbed queue(List<TrackCandidate> queued) {
    EmbedBuilder builder = new EmbedBuilder().setTitle("Queue").setColor(Color.BLUE);
    if (queued.isEmpty()) {
      builder.setDescription("The queue is empty.");
      return builder.build();
    }
    StringBuilder lines = new StringBuilder();
    int shown = Math.min(queued.size(), MAX_QUEUE_LINES);
    for (int i = 0; i < shown; i++) {
      TrackCandidate candidate = queued.get(i);
      lines
          .append(i + 1)
          .append(". ")
          .append(TextSanitizer.escape(candidate.title()))
          .append(" - ")
          .append(TextSanitizer.escape(candidate.artist()))
          .append('\n');
    }
    if (queued.size() > shown) {
      lines.append("…and ").append(queued.size() - shown).append(" more.");
    }
    builder.setDescription(lines.toString().trim());
    return builder.build();
  }

  public static MessageEmbed error(String message) {
    return new EmbedBuilder()
        .setDescription(TextSanitizer.escape(message))
        .setColor(Color.RED)
        .build();
  }

  public static MessageEmbed info(String message) {
    return new EmbedBuilder()
        .setDescription(TextSanitizer.escape(message))
        .setColor(Color.BLUE)
        .build();
  }

  private static String sourceName(Source source) {
    return switch (source) {
      case YOUTUBE -> "YouTube";
      case SOUNDCLOUD -> "SoundCloud";
      case SPOTIFY -> "Spotify";
      case APPLE_MUSIC -> "Apple Music";
    };
  }

  private static String format(Duration duration) {
    if (duration == null) {
      return "live";
    }
    long seconds = duration.getSeconds();
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long rest = seconds % 60;
    if (hours > 0) {
      return String.format("%d:%02d:%02d", hours, minutes, rest);
    }
    return String.format("%d:%02d", minutes, rest);
  }
}
