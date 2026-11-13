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
    return importPreview(tracks, totalDetected, droppedImages, false, 0);
  }

  /** Same, with the non-music warning banner and skipped-line count. */
  public static MessageEmbed importPreview(
      List<dev.chico.bot.parser.ParsedTrack> tracks,
      int totalDetected,
      int droppedImages,
      boolean warned,
      int suspiciousDropped) {
    EmbedBuilder builder = new EmbedBuilder().setTitle("Import preview").setColor(Color.BLUE);
    StringBuilder lines = new StringBuilder();
    if (warned) {
      lines
          .append("⚠ This doesn't look like a playlist — ")
          .append(suspiciousDropped)
          .append(" lines skipped. Are these the songs you'd like to listen?")
          .append(" Press Play all to confirm.")
          .append("\n\n");
    }
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

  /** Preview of a playlist link before anything is enqueued. */
  public static MessageEmbed playlistPreview(
      List<dev.chico.bot.application.PlaylistItem> items,
      int page,
      boolean canPlaySingle,
      String note) {
    EmbedBuilder builder = new EmbedBuilder().setTitle("Playlist preview").setColor(Color.BLUE);
    if (items.isEmpty()) {
      String message =
          note != null && !note.isBlank() ? note : "That playlist is empty.";
      builder.setDescription(TextSanitizer.escape(message));
      return builder.build();
    }
    int pageSize = 10;
    int totalPages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
    int current = Math.min(Math.max(page, 0), totalPages - 1);
    int from = current * pageSize;
    int to = Math.min(from + pageSize, items.size());
    StringBuilder lines = new StringBuilder();
    for (int i = from; i < to; i++) {
      dev.chico.bot.application.PlaylistItem item = items.get(i);
      lines.append(i + 1).append(". ").append(TextSanitizer.escape(item.title()));
      if (item.artist() != null && !item.artist().isBlank()) {
        lines.append(" - ").append(TextSanitizer.escape(item.artist()));
      }
      lines.append('\n');
    }
    builder.setDescription(truncate(lines.toString().trim()));
    String footer = "Showing " + (from + 1) + "–" + to + " of " + items.size() + ".";
    if (note != null && !note.isBlank()) {
      footer += " " + note;
    }
    builder.setFooter(footer);
    return builder.build();
  }

  @Deprecated
  public static MessageEmbed playlistPreview(
      List<dev.chico.bot.application.PlaylistItem> items, int page) {
    return playlistPreview(items, page, false, null);
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

  public static MessageEmbed help() {
    return new EmbedBuilder()
        .setTitle("chicos-bot help")
        .setDescription(truncate(helpText()))
        .setColor(Color.BLUE)
        .build();
  }

  static String helpText() {
    return """
        **Playback**
        `/play [query]` — play by name or link; bare `/play` resumes.
        `/pause` — pause the current track.
        `/skip [target]` — skip the current track or jump to a queue position.
        `/stop` — stop and clear the queue.
        `/nowplaying` — show the current track.
        `/loop [target]` — loop the current track, a queue position, or all.
        `/unloop` — stop any active loop.
        `/exit` — leave voice and clear the queue.

        **Queue**
        `/queue` — post the live queue message.
        `/remove <target>` — remove a queued track by number or name.

        **Import**
        Mention the bot with a playlist screenshot (PNG, JPEG or WebP, up to 10 images). Review the preview, then press **Play all**.
        `@bot p <song>` — same as `/play` from a message.
        Message command **Play music from image** — import from an older screenshot.

        **Links and sources**
        YouTube and SoundCloud links play directly. Apple Music links become a search. Spotify links use the title only (partial support); Spotify playlists are not supported. YouTube and SoundCloud playlist links show a preview before anything is queued.

        **Votes**
        With 3+ people in the voice channel, skipping or removing someone else's track needs a majority. Your own track is immediate. `/stop` and `/exit` are always free.

        **Other**
        `/help` — this message.
        `/ping` — check that the bot is online.
        """.strip();
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
