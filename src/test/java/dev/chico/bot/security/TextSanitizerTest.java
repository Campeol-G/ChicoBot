package dev.chico.bot.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextSanitizerTest {

  @Test
  void mentionsAreNeutralized() {
    String result = TextSanitizer.escape("@everyone @here <@123> <@!456> <@&789> <#012>");

    assertFalse(result.contains("@everyone"), result);
    assertFalse(result.contains("@here"), result);
    assertFalse(result.contains("<@123>"), result);
  }

  @Test
  void markdownIsEscaped() {
    String result = TextSanitizer.escape("**bold** __u__ `code` ~~strike~~ <tag> #hash a|b");

    assertTrue(result.contains("\\*\\*bold\\*\\*"), result);
    assertTrue(result.contains("\\_\\_u\\_\\_"), result);
    assertTrue(result.contains("\\`code\\`"), result);
    assertTrue(result.contains("\\~\\~strike\\~\\~"), result);
    assertTrue(result.contains("bold"), result);
  }

  @Test
  void urlsAreRemoved() {
    String result = TextSanitizer.escape("listen https://evil.test/x here");

    assertFalse(result.contains("https://evil.test/x"), result);
    assertTrue(result.contains("listen"), result);
    assertTrue(result.contains("here"), result);
  }

  @Test
  void outputIsBounded() {
    String result = TextSanitizer.escape("a".repeat(500));

    assertTrue(result.length() <= 201, "length was " + result.length());
  }

  @Test
  void nullAndBlankBecomeEmpty() {
    assertEquals("", TextSanitizer.escape(null));
    assertEquals("", TextSanitizer.escape("   "));
  }
}
