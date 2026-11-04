package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    var ocr = new FakeOcr(List.of(lines(WORDS)));
    var result =
        flow(new FakeDownloader(png), ocr).run(7L, 9L, List.of(attachment("a.png"))).join();
    assertEquals(25, result.tracks().size());
    assertEquals(30, result.totalDetected());
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
}
