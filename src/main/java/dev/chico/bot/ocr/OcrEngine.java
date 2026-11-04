package dev.chico.bot.ocr;

import java.time.Duration;
import java.util.List;

/** Reads text words with positions from a normalized PNG. */
public interface OcrEngine {

  OcrResult read(byte[] png) throws OcrException;
}
