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

  @Test
  void extraLinesInOneRowBecomeTitleOnlyTracks() {
    // Dense rhythm merges five lines into one row: T1 A1 T2(lone) T3 A3.
    // The pair stays paired; nothing after it may vanish silently.
    List<OcrWord> words =
        List.of(
            word("Skyline", 0, 20),
            word("Ocean", 26, 14),
            word("Solitude", 54, 20),
            word("Nights", 86, 20),
            word("Vapor", 114, 14));
    OcrResult ocr = new OcrResult(words, Duration.ZERO);

    List<ParsedTrack> tracks = new DefaultTrackLineParser().parse(ocr);

    assertEquals(
        List.of("Skyline", "Solitude", "Nights", "Vapor"),
        tracks.stream().map(ParsedTrack::title).toList());
    assertEquals("Ocean", tracks.get(0).artist());
  }

  private static OcrWord word(String text, int top, int height) {
    return new OcrWord(text, 0, top, 60, height, 95.0f, 1, 1, top);
  }
}
