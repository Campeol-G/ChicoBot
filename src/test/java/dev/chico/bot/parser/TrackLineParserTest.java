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

  private static OcrWord wordAt(String text, int left, int top, int line) {
    return new OcrWord(text, left, top, 60, 20, 95.0f, 1, 1, line);
  }

  @Test
  void oneLineYieldsTitleOnlyTrack() {
    // POR QUE: the parser pairs title+artist across visual lines; a lone
    // "Artist - Title" line stays one title-only track (no order guessing).
    List<OcrWord> words =
        List.of(
            wordAt("Midnight", 0, 0, 1),
            wordAt("-", 70, 0, 1),
            wordAt("City", 90, 0, 1));
    OcrResult ocr = new OcrResult(words, Duration.ZERO);

    List<ParsedTrack> tracks = new DefaultTrackLineParser().parse(ocr);

    assertEquals(1, tracks.size());
    assertEquals("Midnight - City", tracks.get(0).title());
    assertEquals("", tracks.get(0).artist());
  }

  @Test
  void numberedPrefixStripped() {
    // POR QUE: the number and title share the visual line, so the number's
    // right edge stays close to the title's left edge (no column split) and
    // the title starts left of the content-column fraction.
    List<OcrWord> words =
        List.of(
            new OcrWord("1.", 0, 0, 18, 20, 95.0f, 1, 1, 1),
            new OcrWord("Alpha", 22, 0, 60, 20, 95.0f, 1, 1, 1),
            wordAt("Bravo", 0, 26, 2));
    OcrResult ocr = new OcrResult(words, Duration.ZERO);

    List<ParsedTrack> tracks = new DefaultTrackLineParser().parse(ocr);

    assertEquals(1, tracks.size());
    assertEquals("Alpha", tracks.get(0).title());
    assertEquals("Bravo", tracks.get(0).artist());
  }

  @Test
  void tableHeaderIgnored() {
    List<OcrWord> words =
        List.of(
            wordAt("Title", 0, 0, 1),
            wordAt("Album", 70, 0, 1),
            wordAt("Date", 140, 0, 1),
            wordAt("added", 190, 0, 1),
            wordAt("Alpha", 0, 60, 2),
            wordAt("Bravo", 0, 86, 3));
    OcrResult ocr = new OcrResult(words, Duration.ZERO);

    List<ParsedTrack> tracks = new DefaultTrackLineParser().parse(ocr);

    assertEquals(1, tracks.size());
    assertEquals("Alpha", tracks.get(0).title());
    assertEquals("Bravo", tracks.get(0).artist());
  }

  @Test
  void alternatingGapPairsTitleAndArtist() {
    // POR QUE: real dark prints use a small title->artist gap and a larger
    // between-track gap; the jump threshold must keep each pair together.
    // Truly uniform pitch stays merged (dense fallback, see
    // extraLinesInOneRowBecomeTitleOnlyTracks) pending TSV ground truth.
    List<OcrWord> words =
        List.of(
            wordAt("TitleOne", 0, 0, 1),
            wordAt("ArtistOne", 0, 25, 2),
            wordAt("TitleTwo", 0, 60, 3),
            wordAt("ArtistTwo", 0, 85, 4));
    OcrResult ocr = new OcrResult(words, Duration.ZERO);

    List<ParsedTrack> tracks = new DefaultTrackLineParser().parse(ocr);

    assertEquals(2, tracks.size());
    assertEquals("TitleOne", tracks.get(0).title());
    assertEquals("ArtistOne", tracks.get(0).artist());
    assertEquals("TitleTwo", tracks.get(1).title());
    assertEquals("ArtistTwo", tracks.get(1).artist());
  }

  @Test
  void playIconPrefixStrippedButSingleLetterTitleKept() {
    List<OcrWord> iconLine =
        List.of(
            new OcrWord("D)", 0, 0, 18, 20, 95.0f, 1, 1, 1),
            new OcrWord("sombr", 22, 0, 60, 20, 95.0f, 1, 1, 1));
    OcrResult iconOcr = new OcrResult(iconLine, Duration.ZERO);
    List<ParsedTrack> iconTracks = new DefaultTrackLineParser().parse(iconOcr);

    assertEquals(1, iconTracks.size());
    assertEquals("sombr", iconTracks.get(0).title());

    List<OcrWord> loneE = List.of(wordAt("E", 0, 0, 1));
    List<ParsedTrack> loneTracks =
        new DefaultTrackLineParser().parse(new OcrResult(loneE, Duration.ZERO));

    assertEquals(1, loneTracks.size());
    assertEquals("E", loneTracks.get(0).title());
  }

  @Test
  void emDashNoisePrefixStripped() {
    List<OcrWord> words =
        List.of(
            new OcrWord("——N", 0, 0, 30, 20, 95.0f, 1, 1, 1),
            new OcrWord("More", 34, 0, 40, 20, 95.0f, 1, 1, 1),
            new OcrWord("Than", 78, 0, 40, 20, 95.0f, 1, 1, 1),
            new OcrWord("AW", 122, 0, 30, 20, 95.0f, 1, 1, 1));
    OcrResult ocr = new OcrResult(words, Duration.ZERO);

    List<ParsedTrack> tracks = new DefaultTrackLineParser().parse(ocr);

    assertEquals(1, tracks.size());
    assertEquals("More Than AW", tracks.get(0).title());
  }
}
