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
  private static final int MAX_DESCRIPTION = 4096;

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
    setThumbnail(builder, candidate);
    return builder.build();
  }

  public static MessageEmbed queue(List<TrackCandidate> queued) {
    return queue(queued, dev.chico.bot.audio.TrackScheduler.LoopView.off());
  }

  public static MessageEmbed queue(
      List<TrackCandidate> queued, dev.chico.bot.audio.TrackScheduler.LoopView loop) {
    EmbedBuilder builder = new EmbedBuilder().setTitle("Queue").setColor(Color.BLUE);
    boolean loopAll =
        loop != null && loop.mode() == dev.chico.bot.audio.TrackScheduler.LoopMode.ALL;
    if (queued.isEmpty()) {
      builder.setDescription("The queue is empty.");
      if (loopAll) {
        builder.setFooter("🔁 Looping the queue.");
      }
      return builder.build();
    }
    StringBuilder lines = new StringBuilder();
    int shown = Math.min(queued.size(), MAX_QUEUE_LINES);
    for (int i = 0; i < shown; i++) {
      TrackCandidate candidate = queued.get(i);
      boolean marked =
          loop != null
              && loop.mode() == dev.chico.bot.audio.TrackScheduler.LoopMode.SINGLE
              && loop.markedPosition() == i + 1;
      lines
          .append(marked ? "🔂 " : "")
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
    builder.setDescription(truncate(lines.toString().trim()));
    if (loopAll) {
      builder.setFooter("🔁 Looping the queue.");
    }
    return builder.build();
  }

  public static MessageEmbed error(String message) {
    return new EmbedBuilder()
        .setDescription(TextSanitizer.escape(message))
        .setColor(Color.RED)
        .build();
  }

  /** Preview of an image import. Every track name is escaped here. */
  public static MessageEmbed importPreview(
      List<dev.chico.bot.parser.ParsedTrack> tracks, int totalDetected) {
    return importPreview(tracks, totalDetected, 0);
  }

  /** Preview of an image import with dropped-image count. Every track name is escaped here. */
  public static MessageEmbed importPreview(
      List<dev.chico.bot.parser.ParsedTrack> tracks, int totalDetected, int droppedImages) {
    EmbedBuilder builder = new EmbedBuilder().setTitle("Import preview").setColor(Color.BLUE);
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < tracks.size(); i++) {
      dev.chico.bot.parser.ParsedTrack track = tracks.get(i);
      lines.append(i + 1).append(". ").append(TextSanitizer.escape(track.title()));
      if (!track.artist().isEmpty()) {
        lines.append(" - ").append(TextSanitizer.escape(track.artist()));
      }
      lines.append('\n');
    }
    builder.setDescription(truncate(lines.toString().trim()));
    if (totalDetected > tracks.size()) {
      builder.setFooter(
          "Showing " + tracks.size() + " of " + totalDetected + droppedSuffix(droppedImages));
    } else if (droppedImages > 0) {
      builder.setFooter(tracks.size() + " tracks" + droppedSuffix(droppedImages));
    } else {
      builder.setFooter(tracks.size() + " tracks.");
    }
    return builder.build();
  }

  private static String droppedSuffix(int droppedImages) {
    if (droppedImages <= 0) {
      return ".";
    }
    return ". (+" + droppedImages + " images skipped, max 10).";
  }

  /** Cuts at the last line break so no track line is ever half-shown. */
  private static String truncate(String text) {
    if (text.length() <= MAX_DESCRIPTION) {
      return text;
    }
    int cut = text.lastIndexOf('\n', MAX_DESCRIPTION - 1);
    if (cut < 0) {
      cut = MAX_DESCRIPTION - 1;
    }
    return text.substring(0, cut) + "…";
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

  private static void setThumbnail(EmbedBuilder builder, TrackCandidate candidate) {
    try {
      if (candidate.track() != null && candidate.track().getInfo().artworkUrl != null) {
        builder.setThumbnail(candidate.track().getInfo().artworkUrl);
        return;
      }
    } catch (RuntimeException ignored) {
      // Keep the static fallback below.
    }
    try {
      builder.setThumbnail(sourceIcon(candidate.source()));
    } catch (RuntimeException ignored) {
      // Thumbnail is best-effort; the embed stays usable without it.
    }
  }

  private static String sourceIcon(Source source) {
    return switch (source) {
      case YOUTUBE -> "https://www.youtube.com/favicon.ico";
      case SOUNDCLOUD -> "https://soundcloud.com/favicon.ico";
      case SPOTIFY -> "https://open.spotify.com/favicon.ico";
      case APPLE_MUSIC -> "https://music.apple.com/favicon.ico";
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
