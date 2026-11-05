package dev.chico.bot.application;

import dev.chico.bot.config.Config;
import dev.chico.bot.ocr.ImagePreprocessor;
import dev.chico.bot.ocr.OcrEngine;
import dev.chico.bot.ocr.OcrException;
import dev.chico.bot.ocr.OcrResult;
import dev.chico.bot.parser.ParsedTrack;
import dev.chico.bot.parser.TrackLineParser;
import dev.chico.bot.parser.TrackListMerger;
import dev.chico.bot.security.AttachmentPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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

  /** Session id, the capped preview list and the post-dedup count before the cap. */
  public record ImportResult(UUID sessionId, List<ParsedTrack> tracks, int totalDetected) {
  }

  private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private final ImageDownloader downloader;
  private final OcrEngine ocr;
  private final TrackLineParser parser;
  private final ImagePreprocessor preprocessor;
  private final ImportSession.Store sessions;
  private final Semaphore ocrSlots;
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
    this.maxBytes = config.imgMaxBytes();
    this.maxPixels = config.imgMaxPixels();
    this.maxTracks = config.importMaxTracks();
  }

  public CompletableFuture<ImportResult> run(
      long userId, long guildId, List<AttachmentInput> attachments) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return importSync(userId, guildId, attachments);
          } catch (ImportException e) {
            throw new CompletionException(e);
          }
        },
        EXECUTOR);
  }

  private ImportResult importSync(long userId, long guildId, List<AttachmentInput> attachments)
      throws ImportException {
    List<AttachmentInput> first = attachments.stream().limit(3).toList();
    if (first.isEmpty()) {
      throw new ImportException(Reason.NO_IMAGES, "Send an image with your mention.");
    }
    UUID correlation = UUID.randomUUID();
    org.slf4j.MDC.put("importId", correlation.toString());
    try {
      long start = System.currentTimeMillis();
      List<List<ParsedTrack>> perImage = new ArrayList<>();
      int index = 0;
      for (AttachmentInput in : first) {
        perImage.add(readOne(in, index++));
      }
      List<ParsedTrack> merged = TrackListMerger.merge(perImage);
      int total = merged.size();
      List<ParsedTrack> capped = merged.stream().limit(maxTracks).toList();
      if (capped.isEmpty()) {
        throw new ImportException(Reason.NO_TRACKS, "No tracks found in that image.");
      }
      ImportSession session = sessions.create(userId, guildId, capped);
      log.info(
          "import {} images={} tracks={} total={} elapsedMs={}",
          correlation, first.size(), capped.size(), total, System.currentTimeMillis() - start);
      return new ImportResult(session.id(), capped, total);
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
      ocrSlots.acquire();
      try {
        ocrResult = ocr.read(png);
      } finally {
        ocrSlots.release();
      }
    } catch (OcrException e) {
      throw new ImportException(Reason.OCR_FAILED, "Reading the image timed out. Try again.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ImportException(Reason.OCR_FAILED, "Reading the image timed out. Try again.");
    }
    stage = System.currentTimeMillis() - stage;
    List<ParsedTrack> parsed = parser.parse(ocrResult);
    log.debug("import image={} ocrMs={} words={} tracks={}",
        index, stage, ocrResult.words().size(), parsed.size());
    return parsed;
  }
}
