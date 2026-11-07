package dev.chico.bot.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Live Tesseract run over a real gate print. Needs the binary and the print. */
@Tag("integration")
class TesseractCliEngineIT {

  @Test
  void readsWordsFromGatePrint() throws Exception {
    Assumptions.assumeTrue(tesseractAvailable(), "tesseract not installed");
    Path print = Paths.get("references/white_theme/ytmusic01.png");
    Assumptions.assumeTrue(Files.exists(print), "gate G3 print missing");

    TesseractCliEngine engine = new TesseractCliEngine("por+eng", Duration.ofSeconds(20));
    OcrResult result = engine.read(Files.readAllBytes(print));

    assertFalse(result.words().isEmpty(), "expected words from the gate print");
  }

  @Test
  void readsSecondGoldenPrintWithExpectedTokens() throws Exception {
    Assumptions.assumeTrue(tesseractAvailable(), "tesseract not installed");
    Path print = Paths.get("references/white_theme/ytmusic02.png");
    Assumptions.assumeTrue(Files.exists(print), "gate G4 print missing");

    TesseractCliEngine engine = new TesseractCliEngine("por+eng", Duration.ofSeconds(20));
    OcrResult result = engine.read(Files.readAllBytes(print));
    String bag =
        result.words().stream()
            .map(word -> word.text().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(" "));

    // First expected track of the ytmusic02 golden fixture.
    assertTrue(bag.contains("love"), "expected 'love' in OCR bag: " + bag);
    assertTrue(bag.contains("chicago"), "expected 'chicago' in OCR bag: " + bag);
  }

  @Test
  void timeoutSurfacesTyped() throws Exception {
    Assumptions.assumeTrue(tesseractAvailable(), "tesseract not installed");
    Path print = Paths.get("references/white_theme/ytmusic01.png");
    Assumptions.assumeTrue(Files.exists(print), "gate G3 print missing");

    TesseractCliEngine engine = new TesseractCliEngine("por+eng", Duration.ofMillis(1));

    assertThrows(OcrException.class, () -> engine.read(Files.readAllBytes(print)));
  }

  private static boolean tesseractAvailable() {
    try {
      Process process = new ProcessBuilder("tesseract", "--version").start();
      return process.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
