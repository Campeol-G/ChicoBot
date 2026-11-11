package dev.chico.bot.ocr;

import dev.chico.bot.parser.DefaultTrackLineParser;
import dev.chico.bot.parser.ParsedTrack;
import dev.chico.bot.parser.TrackLineParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class OcrCli {

  private OcrCli() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("usage: OcrCli <image-path>");
      System.exit(2);
    }
    String langs = System.getenv().getOrDefault("OCR_LANGS", "por+eng");
    long timeoutMs = Long.parseLong(System.getenv().getOrDefault("OCR_TIMEOUT_MS", "8000"));
    int psm = Integer.parseInt(System.getenv().getOrDefault("OCR_PSM", "6"));
    boolean binarize =
        Boolean.parseBoolean(System.getenv().getOrDefault("OCR_BINARIZE", "false"));
    byte[] raw = Files.readAllBytes(Path.of(args[0]));
    byte[] png = new ImagePreprocessor(4096L * 4096L, binarize).preprocess(raw);
    OcrResult ocr = new TesseractCliEngine(langs, Duration.ofMillis(timeoutMs), psm).read(png);
    TrackLineParser parser = new DefaultTrackLineParser();
    List<ParsedTrack> tracks = parser.parse(ocr);
    for (ParsedTrack track : tracks) {
      if (track.artist().isEmpty()) {
        System.out.println(track.title());
      } else {
        System.out.println(track.title() + " - " + track.artist());
      }
    }
  }
}
