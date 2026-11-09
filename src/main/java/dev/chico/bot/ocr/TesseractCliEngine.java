package dev.chico.bot.ocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs Tesseract as an isolated external process with timeout and output caps. */
public final class TesseractCliEngine implements OcrEngine {

  private static final int MAX_STDOUT_BYTES = 2 * 1024 * 1024;
  private static final int MAX_STDERR_BYTES = 4 * 1024;

  private final String ocrLangs;
  private final Duration timeout;

  public TesseractCliEngine(String ocrLangs, Duration timeout) {
    this.ocrLangs = ocrLangs;
    this.timeout = timeout;
  }

  @Override
  public OcrResult read(byte[] png) throws OcrException {
    Instant start = Instant.now();
    ProcessBuilder builder =
        new ProcessBuilder(
            "tesseract", "stdin", "stdout", "-l", ocrLangs, "--psm", "6", "tsv");
    Map<String, String> environment = builder.environment();
    environment.put("OMP_THREAD_LIMIT", "1");
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new OcrException("Cannot start tesseract", e);
    }
    CappedOutput stdout = new CappedOutput(process.getInputStream(), MAX_STDOUT_BYTES);
    CappedOutput stderr = new CappedOutput(process.getErrorStream(), MAX_STDERR_BYTES);
    Thread stdoutReader = Thread.ofVirtual().start(stdout);
    Thread stderrReader = Thread.ofVirtual().start(stderr);
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(png);
      stdin.flush();
    } catch (IOException e) {
      shutdownAndJoin(process, stdoutReader, stderrReader);
      throw new OcrException("Cannot feed image to tesseract", e);
    }
    boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      shutdownAndJoin(process, stdoutReader, stderrReader);
      throw new OcrException("Interrupted while waiting for tesseract", e);
    }
    if (!finished) {
      shutdownAndJoin(process, stdoutReader, stderrReader);
      throw new OcrException("Tesseract timed out");
    }
    join(stdoutReader);
    join(stderrReader);
    if (process.exitValue() != 0) {
      throw new OcrException("Tesseract failed: " + stderr.text());
    }
    List<OcrWord> words = TsvParser.parse(stdout.text());
    return new OcrResult(words, Duration.between(start, Instant.now()));
  }

  private static void join(Thread thread) throws OcrException {
    try {
      thread.join(5_000L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OcrException("Interrupted while reading tesseract output", e);
    }
  }

  /**
   * Kills the child and unblocks the pipe readers. Closing the pipes first
   * is what releases a reader blocked in {@code read()}, not the join.
   */
  private static void shutdownAndJoin(Process process, Thread... readers) {
    process.destroyForcibly();
    closeQuietly(process.getInputStream());
    closeQuietly(process.getErrorStream());
    for (Thread reader : readers) {
      try {
        reader.join(5_000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static void closeQuietly(java.io.Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // Best effort: the reader unblocks either way.
    }
  }

  private static final class CappedOutput implements Runnable {
    private final InputStream in;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final int cap;

    CappedOutput(InputStream in, int cap) {
      this.in = in;
      this.cap = cap;
    }

    @Override
    public void run() {
      byte[] buffer = new byte[8192];
      try {
        int read;
        while ((read = in.read(buffer)) != -1) {
          int remaining = cap - out.size();
          if (remaining > 0) {
            out.write(buffer, 0, Math.min(read, remaining));
          }
        }
      } catch (IOException ignored) {
        // Best effort: whatever was captured is enough for diagnostics.
      }
    }

    String text() {
      return out.toString(StandardCharsets.UTF_8);
    }
  }
}
