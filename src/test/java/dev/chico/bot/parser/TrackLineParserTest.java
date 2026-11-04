package dev.chico.bot.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.chico.bot.ocr.OcrResult;
import dev.chico.bot.ocr.OcrWord;
import dev.chico.bot.ocr.TsvParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackLineParserTest {

  private static List<String> parseFixture(String name) throws Exception {
    String tsv =
        Files.readString(Path.of("src/test/resources/parser/" + name + ".tsv"), StandardCharsets.UTF_8);
    List<OcrWord> words = TsvParser.parse(tsv);
    OcrResult ocr = new OcrResult(words, Duration.ZERO);
    return new DefaultTrackLineParser().parse(ocr).stream()
        .map(track -> track.artist().isEmpty()
            ? track.title()
            : track.title() + " | " + track.artist())
        .toList();
  }

  private static List<String> expected(String name) throws Exception {
    String text =
        Files.readString(
            Path.of("src/test/resources/parser/" + name + ".expected.txt"), StandardCharsets.UTF_8);
    return text.lines().toList();
  }

  @Test
  void desktopTableYieldsTitleAndArtistOnly() throws Exception {
    assertEquals(expected("spotify-desktop"), parseFixture("spotify-desktop"));
  }

  @Test
  void youtubeMusicWithoutNumbersYieldsTitleAndArtist() throws Exception {
    assertEquals(expected("ytmusic-no-numbers"), parseFixture("ytmusic-no-numbers"));
  }

  @Test
  void goldenYoutubeMusicPrints() throws Exception {
    assertEquals(expected("ytmusic01"), parseFixture("ytmusic01"));
    assertEquals(expected("ytmusic02"), parseFixture("ytmusic02"));
    assertEquals(expected("ytmusic03"), parseFixture("ytmusic03"));
  }

  @Test
  void goldenSpotifyPrints() throws Exception {
    assertEquals(expected("spotify01"), parseFixture("spotify01"));
    assertEquals(expected("spotify02"), parseFixture("spotify02"));
    assertEquals(expected("spotify05"), parseFixture("spotify05"));
  }
}
