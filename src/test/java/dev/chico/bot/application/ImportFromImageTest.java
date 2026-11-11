package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.chico.bot.config.Config;
import dev.chico.bot.ocr.OcrEngine;
import dev.chico.bot.ocr.OcrResult;
import dev.chico.bot.ocr.OcrWord;
import dev.chico.bot.parser.DefaultTrackLineParser;
import dev.chico.bot.ocr.ImagePreprocessor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImportFromImageTest {

  private static final List<String> WORDS = List.of(
      "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
      "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa",
      "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey",
      "Xray", "Yankee", "Zulu", "Amber", "Boulder", "Cinder", "Ember");

  private static byte[] tinyPng() throws Exception {
    BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static OcrResult lines(List<String> titles) {
    // One visual line per track, spaced widely: the parser groups tightly
    // spaced lines into title+artist rows, so uniform small gaps would merge
    // everything into one row. A 60px pitch keeps each line its own row.
    List<OcrWord> words = new ArrayList<>();
    for (int i = 0; i < titles.size(); i++) {
      words.add(new OcrWord(titles.get(i), 0, i * 60, 60, 20, 95.0f, 1, 1, i + 1));
    }
    return new OcrResult(List.copyOf(words), Duration.ofMillis(10));
  }

  private static AttachmentInput attachment(String name) throws Exception {
    return new AttachmentInput(
        new URI("https://cdn.discordapp.com/attachments/1/2/" + name),
        "image/png",
        1024,
        10,
        10);
  }

  static final class FakeDownloader extends ImageDownloader {
    private final byte[] bytes;

    FakeDownloader(byte[] bytes) {
      super(8_388_608L);
      this.bytes = bytes;
    }

    @Override
    public byte[] download(AttachmentInput in) {
      return bytes;
    }
  }

  static final class FakeOcr implements OcrEngine {
    private final Queue<OcrResult> results;

    FakeOcr(List<OcrResult> results) {
      this.results = new ArrayDeque<>(results);
    }

    @Override
    public OcrResult read(byte[] png) {
      OcrResult next = results.poll();
      if (next == null) {
        fail("OcrEngine called more times than expected");
      }
      return next;
    }
  }

  private static ImportFromImage flow(ImageDownloader downloader, OcrEngine ocr) {
    Config config = new Config(Map.of("DISCORD_TOKEN", "test"));
    return new ImportFromImage(
        config,
        downloader,
        ocr,
        new DefaultTrackLineParser(),
        new ImagePreprocessor(config.imgMaxPixels()),
        new ImportSession.Store());
  }

  @Test
  void mergesBoundaryOverlapsAcrossThreeImages() throws Exception {
    byte[] png = tinyPng();
    var ocr =
        new FakeOcr(
            List.of(
                lines(List.of("Alpha", "Beta", "Gamma")),
                lines(List.of("Gamma", "Delta")),
                lines(List.of("Delta", "Epsilon"))));
    var result =
        flow(new FakeDownloader(png), ocr)
            .run(7L, 9L,
                List.of(attachment("a.png"), attachment("b.png"), attachment("c.png")))
            .join();
    assertEquals(
        List.of("Alpha", "Beta", "Gamma", "Delta", "Epsilon"),
        result.tracks().stream().map(t -> t.title()).toList());
    assertEquals(5, result.totalDetected());
  }

  @Test
  void capsAtImportMaxTracks() throws Exception {
    byte[] png = tinyPng();
    List<String> sixty = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      sixty.add("Track" + i);
    }
    var ocr = new FakeOcr(List.of(lines(sixty)));
    var result =
        flow(new FakeDownloader(png), ocr).run(7L, 9L, List.of(attachment("a.png"))).join();
    assertEquals(50, result.tracks().size());
    assertEquals(60, result.totalDetected());
    assertEquals(0, result.droppedImages());
  }

  @Test
  void eleventhImageCountsAsDropped() throws Exception {
    byte[] png = tinyPng();
    List<OcrResult> results = new ArrayList<>();
    List<AttachmentInput> eleven = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      results.add(lines(List.of("Track" + i)));
      eleven.add(attachment("image" + i + ".png"));
    }
    var ocr = new FakeOcr(results);

    var result = flow(new FakeDownloader(png), ocr).run(7L, 9L, eleven).join();

    assertEquals(10, result.tracks().size());
    assertEquals(10, result.totalDetected());
    assertEquals(1, result.droppedImages());
  }

  @Test
  void codeScreenshotBecomesNoTracks() throws Exception {
    byte[] png = tinyPng();
    var ocr =
        new FakeOcr(
            List.of(
                lines(
                    List.of(
                        "$ sudo apt update",
                        "sudo apt install openjdk-21-jdk",
                        "--no-install-recommends",
                        "Requirements",
                        "Java 26 or newer",
                        "public class Main {",
                        "return value;",
                        "mvn -q package -DskipTests",
                        "java -jar target/chicos-bot.jar"))));

    var failure =
        assertThrows(
            CompletionException.class,
            () -> flow(new FakeDownloader(png), ocr).run(7L, 9L, List.of(attachment("a.png"))).join());

    var cause = (ImportFromImage.ImportException) failure.getCause();
    assertEquals(ImportFromImage.Reason.NO_TRACKS, cause.reason());
  }

  @Test
  void mixedScreenshotWarnsAndKeepsMusic() throws Exception {
    byte[] png = tinyPng();
    var ocr =
        new FakeOcr(
            List.of(
                lines(
                    List.of(
                        "$ docker compose up -d",
                        "Requirements",
                        "npm install --save-dev",
                        "Alpha",
                        "Bravo",
                        "Charlie",
                        "Delta",
                        "Echo",
                        "Foxtrot",
                        "Golf"))));

    var result = flow(new FakeDownloader(png), ocr).run(7L, 9L, List.of(attachment("a.png"))).join();

    assertTrue(result.warned());
    assertEquals(3, result.suspiciousDropped());
    assertEquals(7, result.tracks().size());
  }

  @Test
  void reportsProgressPerImage() throws Exception {
    byte[] png = tinyPng();
    var ocr =
        new FakeOcr(
            List.of(
                lines(List.of("Alpha")),
                lines(List.of("Beta")),
                lines(List.of("Gamma"))));
    List<String> progress = new ArrayList<>();

    flow(new FakeDownloader(png), ocr)
        .run(
            7L,
            9L,
            List.of(attachment("a.png"), attachment("b.png"), attachment("c.png")),
            (done, total) -> progress.add(done + "/" + total))
        .join();

    assertEquals(List.of("1/3", "2/3", "3/3"), progress);
  }

  @Test
  void fifthImageIsRead() throws Exception {
    byte[] png = tinyPng();
    var ocr =
        new FakeOcr(
            List.of(
                lines(List.of("Alpha")),
                lines(List.of("Beta")),
                lines(List.of("Gamma")),
                lines(List.of("Delta")),
                lines(List.of("Epsilon"))));
    var result =
        flow(new FakeDownloader(png), ocr)
            .run(
                7L,
                9L,
                List.of(
                    attachment("a.png"),
                    attachment("b.png"),
                    attachment("c.png"),
                    attachment("d.png"),
                    attachment("e.png")))
            .join();
    assertEquals(5, result.tracks().size());
    assertEquals(5, result.totalDetected());
  }

  @Test
  void invalidAttachmentFailsTyped() throws Exception {
    byte[] png = tinyPng();
    var ocr = new FakeOcr(List.of());
    var bad =
        new AttachmentInput(
            new URI("https://cdn.discordapp.com/attachments/1/2/notes.txt"),
            "text/plain",
            100,
            10,
            10);
    try {
      flow(new FakeDownloader(png), ocr).run(7L, 9L, List.of(bad)).join();
      fail("expected ImportException");
    } catch (CompletionException e) {
      assertTrue(e.getCause() instanceof ImportFromImage.ImportException);
    }
  }

  static final class ThrowingDownloader extends ImageDownloader {
    private final ImportFromImage.ImportException failure;

    ThrowingDownloader(ImportFromImage.ImportException failure) {
      super(8_388_608L);
      this.failure = failure;
    }

    @Override
    public byte[] download(AttachmentInput in) throws ImportFromImage.ImportException {
      throw failure;
    }
  }

  static final class ThrowingOcr implements OcrEngine {
    private final dev.chico.bot.ocr.OcrException failure;

    ThrowingOcr(dev.chico.bot.ocr.OcrException failure) {
      this.failure = failure;
    }

    @Override
    public OcrResult read(byte[] png) throws dev.chico.bot.ocr.OcrException {
      throw failure;
    }
  }

  private static ImportFromImage.Reason reasonOf(CompletionException e) {
    assertTrue(e.getCause() instanceof ImportFromImage.ImportException);
    return ((ImportFromImage.ImportException) e.getCause()).reason();
  }

  @Test
  void noImagesThrowsNoImages() {
    var ocr = new FakeOcr(List.of());
    try {
      flow(new FakeDownloader(new byte[0]), ocr).run(7L, 9L, List.of()).join();
      fail("expected ImportException");
    } catch (CompletionException e) {
      assertEquals(ImportFromImage.Reason.NO_IMAGES, reasonOf(e));
    }
  }

  @Test
  void downloadFailurePropagatesTyped() throws Exception {
    var ocr = new FakeOcr(List.of());
    var downloader =
        new ThrowingDownloader(
            new ImportFromImage.ImportException(
                ImportFromImage.Reason.DOWNLOAD_FAILED, "Could not download the image."));
    try {
      flow(downloader, ocr).run(7L, 9L, List.of(attachment("a.png"))).join();
      fail("expected ImportException");
    } catch (CompletionException e) {
      assertEquals(ImportFromImage.Reason.DOWNLOAD_FAILED, reasonOf(e));
    }
  }

  @Test
  void ocrFailurePropagatesTyped() throws Exception {
    byte[] png = tinyPng();
    var ocr = new ThrowingOcr(new dev.chico.bot.ocr.OcrException("timeout"));
    try {
      flow(new FakeDownloader(png), ocr).run(7L, 9L, List.of(attachment("a.png"))).join();
      fail("expected ImportException");
    } catch (CompletionException e) {
      assertEquals(ImportFromImage.Reason.OCR_FAILED, reasonOf(e));
    }
  }

  @Test
  void imageRejectedPropagatesTyped() throws Exception {
    // 16x16 GIF magic: declared PNG passes the reported check, real bytes fail it.
    byte[] gif = new byte[] {'G', 'I', 'F', '8', '9', 'a', 16, 0, 16, 0, 0, 0, 0, 0};
    var ocr = new FakeOcr(List.of());
    try {
      flow(new FakeDownloader(gif), ocr).run(7L, 9L, List.of(attachment("a.png"))).join();
      fail("expected ImportException");
    } catch (CompletionException e) {
      assertEquals(ImportFromImage.Reason.IMAGE_REJECTED, reasonOf(e));
    }
  }

  @Test
  void noTracksThrowsNoTracks() throws Exception {
    byte[] png = tinyPng();
    var ocr = new FakeOcr(List.of(new OcrResult(List.of(), Duration.ofMillis(10))));
    try {
      flow(new FakeDownloader(png), ocr).run(7L, 9L, List.of(attachment("a.png"))).join();
      fail("expected ImportException");
    } catch (CompletionException e) {
      assertEquals(ImportFromImage.Reason.NO_TRACKS, reasonOf(e));
    }
  }

  @Test
  void importBusyFailsFast() throws Exception {
    byte[] png = tinyPng();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    OcrEngine blocking =
        bs -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new dev.chico.bot.ocr.OcrException("interrupted", e);
          }
          return lines(List.of("Alpha"));
        };
    Config busyConfig =
        new Config(
            Map.of(
                "DISCORD_TOKEN", "test", "OCR_MAX_CONCURRENCY", "1", "OCR_TIMEOUT_MS", "200"));
    ImportFromImage flow =
        new ImportFromImage(
            busyConfig,
            new FakeDownloader(png),
            blocking,
            new DefaultTrackLineParser(),
            new ImagePreprocessor(busyConfig.imgMaxPixels()),
            new ImportSession.Store());
    var first = flow.run(7L, 9L, List.of(attachment("a.png")));
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    try {
      flow.run(7L, 9L, List.of(attachment("b.png")))
          .orTimeout(3, TimeUnit.SECONDS)
          .join();
      fail("expected ImportException");
    } catch (CompletionException e) {
      assertEquals(ImportFromImage.Reason.OCR_FAILED, reasonOf(e));
    } finally {
      release.countDown();
    }
    assertEquals(1, first.join().tracks().size());
  }
}
