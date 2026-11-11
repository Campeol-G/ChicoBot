package dev.chico.bot.application;

import dev.chico.bot.config.Config;
import dev.chico.bot.ocr.ImagePreprocessor;
import dev.chico.bot.ocr.OcrEngine;
import dev.chico.bot.ocr.OcrException;
import dev.chico.bot.ocr.OcrResult;
import dev.chico.bot.parser.ParsedTrack;
import dev.chico.bot.parser.NonMusicGuard;
import dev.chico.bot.parser.TrackLineParser;
import dev.chico.bot.parser.TrackListMerger;
import dev.chico.bot.security.AttachmentPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs the screenshot-to-track-list pipeline and stores the preview session. */
public final class ImportFromImage {

  private static final Logger log = LoggerFactory.getLogger(ImportFromImage.class);

  /** Typed import failure; the message is safe to show to the user. */
  public static final class ImportException extends Exception {
    private final Reason reason;

    public ImportException(Reason reason, String message) {
      super(message);
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }

  public enum Reason {
    NO_IMAGES,
    ATTACHMENT_REJECTED,
    DOWNLOAD_FAILED,
    IMAGE_REJECTED,
    OCR_FAILED,
    NO_TRACKS
  }

  /** Session id, the capped preview list, the post-dedup count and dropped image count. */
  public record ImportResult(
      UUID sessionId,
      List<ParsedTrack> tracks,
      int totalDetected,
      int droppedImages,
      boolean warned,
      int suspiciousDropped) {

    public ImportResult(
        UUID sessionId, List<ParsedTrack> tracks, int totalDetected, int droppedImages) {
      this(sessionId, tracks, totalDetected, droppedImages, false, 0);
    }
  }

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  static final int MAX_IMAGES = 10;

  /** Called once per image after download+OCR+parse complete. */
  public interface ImportProgress {
    void onImage(int completed, int total);
  }

  private final ImageDownloader downloader;
  private final OcrEngine ocr;
  private final TrackLineParser parser;
  private final ImagePreprocessor preprocessor;
  private final ImportSession.Store sessions;
  private final Semaphore ocrSlots;
  private final Duration ocrTimeout;
  private final long maxBytes;
  private final long maxPixels;
  private final int maxTracks;

  public ImportFromImage(
      Config config,
      ImageDownloader downloader,
      OcrEngine ocr,
      TrackLineParser parser,
      ImagePreprocessor preprocessor,
      ImportSession.Store sessions) {
    this.downloader = downloader;
    this.ocr = ocr;
    this.parser = parser;
    this.preprocessor = preprocessor;
    this.sessions = sessions;
    this.ocrSlots = new Semaphore(config.ocrMaxConcurrency());
    this.ocrTimeout = Duration.ofMillis(config.ocrTimeoutMs());
    this.maxBytes = config.imgMaxBytes();
    this.maxPixels = config.imgMaxPixels();
    this.maxTracks = config.importMaxTracks();
  }

  public CompletableFuture<ImportResult> run(
      long userId, long guildId, List<AttachmentInput> attachments) {
    return run(userId, guildId, attachments, (completed, total) -> {
    });
  }

  public CompletableFuture<ImportResult> run(
      long userId,
      long guildId,
      List<AttachmentInput> attachments,
      ImportProgress progress) {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return importSync(userId, guildId, attachments, progress);
              } catch (ImportException e) {
                throw new CompletionException(e);
              }
            },
            EXECUTOR)
        // POR QUE: one slot covers download+preprocess+OCR per image, so the
        // total fits in images * (HTTP timeout + OCR timeout) plus slack.
        .orTimeout(
            MAX_IMAGES * (10_000 + ocrTimeout.toMillis()) + 5_000,
            TimeUnit.MILLISECONDS)
        .exceptionallyCompose(
            failure -> {
              Throwable cause =
                  failure instanceof CompletionException ? failure.getCause() : failure;
              if (cause instanceof TimeoutException) {
                return CompletableFuture.failedFuture(
                    new CompletionException(
                        new ImportException(
                            Reason.OCR_FAILED, "Reading the image timed out. Try again.")));
              }
              return CompletableFuture.failedFuture(failure);
            });
  }

  private ImportResult importSync(
      long userId,
      long guildId,
      List<AttachmentInput> attachments,
      ImportProgress progress)
      throws ImportException {
    List<AttachmentInput> first = attachments.stream().limit(MAX_IMAGES).toList();
    if (first.isEmpty()) {
      throw new ImportException(Reason.NO_IMAGES, "Send an image with your mention.");
    }
    int dropped = attachments.size() - first.size();
    UUID correlation = UUID.randomUUID();
    org.slf4j.MDC.put("importId", correlation.toString());
    try {
      long start = System.currentTimeMillis();
      List<List<ParsedTrack>> perImage = new ArrayList<>();
      int index = 0;
      for (AttachmentInput in : first) {
        perImage.add(readOne(in, index++));
        progress.onImage(index, first.size());
      }
      List<ParsedTrack> merged = TrackListMerger.merge(perImage);
      NonMusicGuard.Result guard = NonMusicGuard.evaluate(merged);
      if (guard.verdict() == NonMusicGuard.Verdict.REJECT) {
        throw new ImportException(
            Reason.NO_TRACKS,
            "This looks like code or an article, not a playlist. Send a playlist screenshot.");
      }
      List<ParsedTrack> usable =
          guard.verdict() == NonMusicGuard.Verdict.WARN ? guard.kept() : merged;
      int total = usable.size();
      List<ParsedTrack> capped = usable.stream().limit(maxTracks).toList();
      if (capped.isEmpty()) {
        throw new ImportException(Reason.NO_TRACKS, "No tracks found in that image.");
      }
      ImportSession session = sessions.create(userId, guildId, capped);
      log.info(
          "import {} images={} tracks={} total={} dropped={} suspicious={} warned={} elapsedMs={}",
          correlation,
          first.size(),
          capped.size(),
          total,
          dropped,
          guard.dropped(),
          guard.verdict() == NonMusicGuard.Verdict.WARN,
          System.currentTimeMillis() - start);
      return new ImportResult(
          session.id(),
          capped,
          total,
          dropped,
          guard.verdict() == NonMusicGuard.Verdict.WARN,
          guard.dropped());
    } finally {
      org.slf4j.MDC.clear();
    }
  }

  private List<ParsedTrack> readOne(AttachmentInput in, int index) throws ImportException {
    if (in == null
        || in.url() == null
        || AttachmentPolicy.validateReported(in, maxBytes, maxPixels)
            instanceof AttachmentPolicy.Result.Rejected) {
      log.warn(
          "import rejected reason={} contentType={} size={} dims={}x{}",
          Reason.ATTACHMENT_REJECTED,
          in == null ? null : in.contentType(),
          in == null ? -1 : in.size(),
          in == null ? -1 : in.width(),
          in == null ? -1 : in.height());
      throw new ImportException(
          Reason.ATTACHMENT_REJECTED, "That image is not supported. Send a PNG or JPEG.");
    }
    boolean slot = false;
    try {
      // POR QUE: the slot covers download+preprocess too — those hold the
      // largest transient buffers, so bounding only OCR still OOMs.
      slot = ocrSlots.tryAcquire(ocrTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      if (!slot) {
        throw new ImportException(
            Reason.OCR_FAILED, "The bot is busy right now. Try again.");
      }
      return readOneGuarded(in, index);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ImportException(Reason.OCR_FAILED, "Reading the image timed out. Try again.");
    } finally {
      if (slot) {
        ocrSlots.release();
      }
    }
  }

  private List<ParsedTrack> readOneGuarded(AttachmentInput in, int index) throws ImportException {
    long stage = System.currentTimeMillis();
    byte[] raw = downloader.download(in);
    if (AttachmentPolicy.validateBytes(raw, maxPixels)
        instanceof AttachmentPolicy.Result.Rejected rejected) {
      log.warn(
          "import rejected reason={} bytes={} policy={}",
          Reason.IMAGE_REJECTED, raw.length, rejected.reason());
      throw new ImportException(
          Reason.IMAGE_REJECTED, "That image is not supported. Send a PNG or JPEG.");
    }
    byte[] png;
    try {
      png = preprocessor.preprocess(raw);
    } catch (OcrException e) {
      throw new ImportException(
          Reason.IMAGE_REJECTED, "That image could not be read. Try another screenshot.");
    }
    log.debug("import image={} downloadPreprocessMs={} bytes={}",
        index, System.currentTimeMillis() - stage, raw.length);
    OcrResult ocrResult;
    stage = System.currentTimeMillis();
    try {
      ocrResult = ocr.read(png);
    } catch (OcrException e) {
      throw new ImportException(Reason.OCR_FAILED, "Reading the image timed out. Try again.");
    }
    stage = System.currentTimeMillis() - stage;
    List<ParsedTrack> parsed = parser.parse(ocrResult);
    log.debug("import image={} ocrMs={} words={} tracks={}",
        index, stage, ocrResult.words().size(), parsed.size());
    return parsed;
  }
}
