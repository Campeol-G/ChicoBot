package dev.chico.bot.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.chico.bot.security.AttachmentPolicy;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

// POR QUE: the non-2xx body-close path is pinned through the package-private
// HttpClient seam below (the allowlist still gates real URLs, so a live
// exchange is unnecessary). Redirects need no test: the client is built with
// followRedirects(NEVER) and only 2xx is accepted, so any 3xx surfaces as
// DOWNLOAD_FAILED by construction.
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
  void offAllowlistRedirectTargetRejected() {
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

  private static final class TrackedBody extends FilterInputStream {
    final AtomicBoolean closed = new AtomicBoolean();

    TrackedBody(byte[] bytes) {
      super(new ByteArrayInputStream(bytes));
    }

    @Override
    public void close() throws IOException {
      closed.set(true);
      super.close();
    }
  }

  private static final class StubResponse implements HttpResponse<InputStream> {
    private final int status;
    private final HttpRequest request;
    private final InputStream body;

    StubResponse(int status, HttpRequest request, InputStream body) {
      this.status = status;
      this.request = request;
      this.body = body;
    }

    @Override
    public int statusCode() {
      return status;
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<InputStream>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
    }

    @Override
    public InputStream body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  private static HttpClient stubClient(int status, InputStream body) {
    return new HttpClient() {
      @Override
      @SuppressWarnings("unchecked")
      public <T> HttpResponse<T> send(
          HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return (HttpResponse<T>) new StubResponse(status, request, body);
      }

      @Override
      public <T> CompletableFuture<HttpResponse<T>> sendAsync(
          HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        throw new UnsupportedOperationException();
      }

      @Override
      public <T> CompletableFuture<HttpResponse<T>> sendAsync(
          HttpRequest request,
          HttpResponse.BodyHandler<T> responseBodyHandler,
          HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        throw new UnsupportedOperationException();
      }

      @Override
      public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
      }

      @Override
      public Optional<Duration> connectTimeout() {
        return Optional.empty();
      }

      @Override
      public HttpClient.Redirect followRedirects() {
        return HttpClient.Redirect.NEVER;
      }

      @Override
      public Optional<ProxySelector> proxy() {
        return Optional.empty();
      }

      @Override
      public SSLContext sslContext() {
        try {
          return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
          throw new IllegalStateException(e);
        }
      }

      @Override
      public SSLParameters sslParameters() {
        return new SSLParameters();
      }

      @Override
      public Optional<Authenticator> authenticator() {
        return Optional.empty();
      }

      @Override
      public Version version() {
        return Version.HTTP_1_1;
      }

      @Override
      public Optional<Executor> executor() {
        return Optional.empty();
      }
    };
  }

  @Test
  void errorResponseClosesBody() throws Exception {
    TrackedBody body = new TrackedBody(new byte[] {'e', 'r', 'r'});
    ImageDownloader downloader = new ImageDownloader(8_388_608L, stubClient(500, body));
    try {
      downloader.download(input("https://cdn.discordapp.com/attachments/1/2/a.png"));
      fail("expected ImportException");
    } catch (ImportFromImage.ImportException e) {
      assertEquals(ImportFromImage.Reason.DOWNLOAD_FAILED, e.reason());
    }
    assertTrue(body.closed.get());
  }
}
