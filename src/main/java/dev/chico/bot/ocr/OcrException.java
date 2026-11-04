package dev.chico.bot.ocr;

/** Failure in any OCR stage: invalid image, oversized input or engine error. */
public class OcrException extends Exception {

  public OcrException(String message) {
    super(message);
  }

  public OcrException(String message, Throwable cause) {
    super(message, cause);
  }
}
