package dev.chico.bot.application;

import dev.chico.bot.security.AttachmentPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Downloads Discord CDN attachments with strict limits. Subclassed by tests. */
public class ImageDownloader {

  private final HttpClient client;
  private final long maxBytes;

  public ImageDownloader(long maxBytes) {
    this.maxBytes = maxBytes;
    this.client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
  }

  public byte[] download(AttachmentInput in) throws ImportFromImage.ImportException {
    URI uri = in.url();
    if (uri == null || !AttachmentPolicy.isAllowedDownloadUrl(uri)) {
      throw new ImportFromImage.ImportException(
          ImportFromImage.Reason.DOWNLOAD_FAILED, "That image link is not allowed.");
    }
    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
    } catch (IllegalArgumentException e) {
      throw new ImportFromImage.ImportException(
          ImportFromImage.Reason.DOWNLOAD_FAILED, "That image link is not allowed.");
    }
    HttpResponse<InputStream> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ImportFromImage.ImportException(
          ImportFromImage.Reason.DOWNLOAD_FAILED, "Could not download the image.");
    }
    if (response.statusCode() / 100 != 2) {
      throw new ImportFromImage.ImportException(
          ImportFromImage.Reason.DOWNLOAD_FAILED, "Could not download the image.");
    }
    try (InputStream body = response.body()) {
      return readCapped(body);
    } catch (IOException e) {
      throw new ImportFromImage.ImportException(
          ImportFromImage.Reason.DOWNLOAD_FAILED, "Could not download the image.");
    }
  }

  private byte[] readCapped(InputStream body)
      throws IOException, ImportFromImage.ImportException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = body.read(buffer)) != -1) {
      if ((long) out.size() + read > maxBytes) {
        throw new ImportFromImage.ImportException(
            ImportFromImage.Reason.ATTACHMENT_REJECTED, "That image is too large.");
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }
}
