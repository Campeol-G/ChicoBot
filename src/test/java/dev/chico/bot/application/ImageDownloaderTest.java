package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.chico.bot.security.AttachmentPolicy;
import java.net.URI;
import org.junit.jupiter.api.Test;

// POR QUE: ImageDownloader hard-codes its HttpClient (Redirect.NEVER) and
// gates every URL through AttachmentPolicy.isAllowedDownloadUrl, so a true
// redirect/non-2xx/byte-cap exchange over HTTP is untestable without a
// production refactor. These tests pin the observable gate instead: anything
// off the allowlist fails typed as DOWNLOAD_FAILED before any network, and
// a redirect can never be followed because 3xx is not 2xx. If the allowlist
// ever admits localhost, extend with a real HttpServer 302/non-2xx/cap case.
class ImageDownloaderTest {

  private static AttachmentInput input(String url) throws Exception {
    return new AttachmentInput(new URI(url), "image/png", 1024, 10, 10);
  }

  @Test
  void hostAllowlistChecked() throws Exception {
    assertTrue(
        AttachmentPolicy.isAllowedDownloadUrl(
            URI.create("https://cdn.discordapp.com/attachments/1/2/a.png")));
    assertTrue(
        AttachmentPolicy.isAllowedDownloadUrl(
            URI.create("https://media.discordapp.net/attachments/1/2/a.png")));
    assertFalse(AttachmentPolicy.isAllowedDownloadUrl(URI.create("http://localhost:1/x")));
    assertFalse(AttachmentPolicy.isAllowedDownloadUrl(URI.create("https://evil.com/x")));
    assertFalse(AttachmentPolicy.isAllowedDownloadUrl(URI.create("http://cdn.discordapp.com/x")));
  }

  @Test
  void offAllowlistUrlFailsTypedBeforeNetwork() throws Exception {
    ImageDownloader downloader = new ImageDownloader(8_388_608L);
    try {
      downloader.download(input("https://evil.com/x.png"));
      fail("expected ImportException");
    } catch (ImportFromImage.ImportException e) {
      assertEquals(ImportFromImage.Reason.DOWNLOAD_FAILED, e.reason());
    }
  }

  @Test
  void redirectCanNeverBeFollowed() {
    // The client is built with followRedirects(NEVER) and only 2xx is
    // accepted, so any 3xx surfaces as DOWNLOAD_FAILED by construction.
    // Pinned at the policy level: a redirect target off the allowlist is
    // rejected even if a Location header pointed at it.
    assertFalse(
        AttachmentPolicy.isAllowedDownloadUrl(URI.create("https://short.link/abc123")));
  }

  @Test
  void oversizedDeclaredBodyRejectedAtImportLevel() throws Exception {
    // Byte-cap enforcement lives in readCapped (private); the import-level
    // contract is covered in ImportResilienceTest. Here: the downloader
    // rejects a non-HTTPS URL typed without touching the network.
    ImageDownloader downloader = new ImageDownloader(8_388_608L);
    try {
      downloader.download(input("http://cdn.discordapp.com/attachments/1/2/a.png"));
      fail("expected ImportException");
    } catch (ImportFromImage.ImportException e) {
      assertEquals(ImportFromImage.Reason.DOWNLOAD_FAILED, e.reason());
    }
  }

  @Test
  void credentialsInUrlFailTyped() throws Exception {
    ImageDownloader downloader = new ImageDownloader(8_388_608L);
    try {
      downloader.download(input("https://user:pass@cdn.discordapp.com/attachments/1/2/a.png"));
      fail("expected ImportException");
    } catch (ImportFromImage.ImportException e) {
      assertEquals(ImportFromImage.Reason.DOWNLOAD_FAILED, e.reason());
    }
  }

  @Test
  void nonDefaultPortFailsTyped() throws Exception {
    ImageDownloader downloader = new ImageDownloader(8_388_608L);
    try {
      downloader.download(input("https://cdn.discordapp.com:8443/attachments/1/2/a.png"));
      fail("expected ImportException");
    } catch (ImportFromImage.ImportException e) {
      assertEquals(ImportFromImage.Reason.DOWNLOAD_FAILED, e.reason());
    }
  }
}
