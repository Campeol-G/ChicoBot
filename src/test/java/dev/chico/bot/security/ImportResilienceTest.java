package dev.chico.bot.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.chico.bot.application.AttachmentInput;
import dev.chico.bot.application.ImageDownloader;
import dev.chico.bot.application.ImportFromImage;
import dev.chico.bot.application.ImportSession;
import dev.chico.bot.config.Config;
import dev.chico.bot.ocr.ImagePreprocessor;
import dev.chico.bot.ocr.OcrEngine;
import dev.chico.bot.ocr.OcrResult;
import dev.chico.bot.ocr.OcrWord;
import dev.chico.bot.parser.DefaultTrackLineParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Hostile inputs fail typed inside the future; logs carry no user content. */
class ImportResilienceTest {

  private static final String TOKEN = "tok-live-ABCDEF-123456";
  private static final String MARKER = "LEAK-MARKER-9Z9Z";

  private static byte[] tinyPng() throws Exception {
    BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static byte[] forgedPngHeader() {
    byte[] bytes = new byte[8 + 8 + 13 + 4];
    byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(signature, 0, bytes, 0, 8);
    bytes[11] = 13;
    bytes[12] = 'I';
    bytes[13] = 'H';
    bytes[14] = 'D';
    bytes[15] = 'R';
    int size = 20000;
    bytes[16] = (byte) (size >> 24);
    bytes[17] = (byte) (size >> 16);
    bytes[18] = (byte) (size >> 8);
    bytes[19] = (byte) size;
    bytes[20] = (byte) (size >> 24);
    bytes[21] = (byte) (size >> 16);
    bytes[22] = (byte) (size >> 8);
    bytes[23] = (byte) size;
    return bytes;
  }

  static final class BytesDownloader extends ImageDownloader {
    private final byte[] bytes;

    BytesDownloader(byte[] bytes) {
      super(8_388_608L);
      this.bytes = bytes;
    }

    @Override
    public byte[] download(AttachmentInput in) {
      return bytes;
    }
  }

  static final class FixedOcr implements OcrEngine {
    private final OcrResult result;

    FixedOcr(OcrResult result) {
      this.result = result;
    }

    @Override
    public OcrResult read(byte[] png) {
      return result;
    }
  }

  private static ImportFromImage flow(ImageDownloader downloader, OcrEngine ocr) {
    Config config = new Config(Map.of("DISCORD_TOKEN", TOKEN));
    return new ImportFromImage(
        config,
        downloader,
        ocr,
        new DefaultTrackLineParser(),
        new ImagePreprocessor(config.imgMaxPixels()),
        new ImportSession.Store());
  }

  private static AttachmentInput input(String name, String contentType, byte[] bytes)
      throws Exception {
    return new AttachmentInput(
        new URI("https://cdn.discordapp.com/attachments/1/2/" + name),
        contentType, bytes.length, 10, 10);
  }

  private static ImportFromImage.ImportException failureOf(CompletableFuture<?> future) {
    try {
      future.join();
    } catch (CompletionException e) {
      assertInstanceOf(ImportFromImage.ImportException.class, e.getCause());
      return (ImportFromImage.ImportException) e.getCause();
    }
    fail("expected the import to fail");
    throw new IllegalStateException("unreachable");
  }

  private static OcrResult noWords() {
    return new OcrResult(List.of(), Duration.ofMillis(5));
  }

  @Test
  void corruptedBytesFailTyped() throws Exception {
    byte[] garbage = {0, 1, 2, 3, 4, 5};
    var future =
        flow(new BytesDownloader(garbage), new FixedOcr(noWords()))
            .run(1L, 2L, List.of(input("x.png", "image/png", garbage)));
    assertEquals(
        ImportFromImage.Reason.IMAGE_REJECTED, failureOf(future).reason());
  }

  @Test
  void truncatedPngFailsTyped() throws Exception {
    byte[] full = tinyPng();
    byte[] cut = new byte[40];
    System.arraycopy(full, 0, cut, 0, 40);
    var future =
        flow(new BytesDownloader(cut), new FixedOcr(noWords()))
            .run(1L, 2L, List.of(input("cut.png", "image/png", cut)));
    assertEquals(
        ImportFromImage.Reason.IMAGE_REJECTED, failureOf(future).reason());
  }

  @Test
  void hugeHeaderDimensionsFailTyped() throws Exception {
    byte[] forged = forgedPngHeader();
    var future =
        flow(new BytesDownloader(forged), new FixedOcr(noWords()))
            .run(1L, 2L, List.of(input("huge.png", "image/png", forged)));
    assertEquals(
        ImportFromImage.Reason.IMAGE_REJECTED, failureOf(future).reason());
  }

  @Test
  void fakeContentTypeFailsTyped() throws Exception {
    byte[] text = "hello world".getBytes();
    var future =
        flow(new BytesDownloader(text), new FixedOcr(noWords()))
            .run(1L, 2L, List.of(input("notes.txt", "text/plain", text)));
    assertEquals(
        ImportFromImage.Reason.ATTACHMENT_REJECTED, failureOf(future).reason());
  }

  @Test
  void logsCarryCorrelationIdButNoUserContent() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(ImportFromImage.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level previous = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    logger.addAppender(appender);
    try {
      byte[] png = tinyPng();
      OcrResult ocr =
          new OcrResult(
              List.of(new OcrWord(MARKER + " @everyone", 0, 0, 60, 20, 95.0f, 1, 1, 1)),
              Duration.ofMillis(5));
      flow(new BytesDownloader(png), new FixedOcr(ocr))
          .run(1L, 2L, List.of(input("a.png", "image/png", png)))
          .join();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previous);
    }
    assertTrue(!appender.list.isEmpty(), "expected import log events");
    boolean correlationSeen = false;
    for (ILoggingEvent event : appender.list) {
      String message = event.getFormattedMessage();
      assertTrue(!message.contains(MARKER), "OCR text leaked into logs: " + message);
      assertTrue(!message.contains(TOKEN), "token leaked into logs");
      if (event.getMDCPropertyMap().containsKey("importId")) {
        correlationSeen = true;
      }
    }
    assertTrue(correlationSeen, "expected an importId in the MDC of import logs");
  }

  @Test
  void attachmentRejectionLogsReasonAndReportedMetadata() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(ImportFromImage.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      byte[] png = tinyPng();
      var oversized =
          new AttachmentInput(
              new URI("https://cdn.discordapp.com/attachments/1/2/big.png"),
              "image/png",
              9_000_000L,
              1290,
              2796);
      try {
        flow(new BytesDownloader(png), new FixedOcr(noWords()))
            .run(1L, 2L, List.of(oversized))
            .join();
        fail("expected the import to fail");
      } catch (CompletionException e) {
        assertInstanceOf(ImportFromImage.ImportException.class, e.getCause());
      }
    } finally {
      logger.detachAppender(appender);
    }
    boolean diagnosticSeen =
        appender.list.stream()
            .anyMatch(
                event ->
                    event.getFormattedMessage().contains("ATTACHMENT_REJECTED")
                        && event.getFormattedMessage().contains("9000000"));
    assertTrue(diagnosticSeen, "expected a rejection log with reason and reported size");
  }
}
