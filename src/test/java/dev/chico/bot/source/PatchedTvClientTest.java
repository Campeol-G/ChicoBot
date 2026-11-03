package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PatchedTvClientTest {

  @Test
  void usesUserAgentAcceptedByYoutube() {
    PatchedTvClient client = new PatchedTvClient();

    assertEquals("TVHTML5", client.getIdentifier());
    assertEquals(
        "Mozilla/5.0 (PlayStation; PlayStation 4/12.00)"
            + " AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15",
        client.getBaseClientConfig(null).getUserAgent());
  }
}
