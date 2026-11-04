package dev.chico.bot.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

  @Test
  void ligaturesNormalized() {
    assertEquals("fi", TextNormalizer.normalize("\uFB01"));
  }

  @Test
  void controlZeroWidthAndBidiRemoved() {
    String dirty = "a\u0007b\u200Bc\u200Ed\uFEFFe\u202Af\u2066g\u061Ch";
    assertEquals("abcdefgh", TextNormalizer.normalize(dirty));
  }

  @Test
  void spacesCollapsed() {
    assertEquals("a b c", TextNormalizer.normalize("a   b\tc"));
  }

  @Test
  void trailingEllipsisRemoved() {
    assertEquals("Elemental Music (feat. Melode", TextNormalizer.normalize("Elemental Music (feat. Melode..."));
    assertEquals("More Than A Woman", TextNormalizer.normalize("More Than A Woman \u2026"));
  }

  @Test
  void overlongTextTruncated() {
    String longText = "a".repeat(150);

    String result = TextNormalizer.normalize(longText);

    assertEquals(100, result.length());
  }

  @Test
  void urlFragmentsDropped() {
    assertEquals(
        "listen here", TextNormalizer.normalize("listen https://example.com/x here"));
    assertEquals("title", TextNormalizer.normalize("title www.example.com/y"));
  }

  @Test
  void mentionsKeptAsPlainText() {
    String result = TextNormalizer.normalize("shout @everyone now");

    assertTrue(result.contains("@everyone"), result);
    assertFalse(result.contains("@\u200B"), result);
  }
}
