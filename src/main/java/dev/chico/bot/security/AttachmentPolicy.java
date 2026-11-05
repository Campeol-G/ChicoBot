package dev.chico.bot.security;

import dev.chico.bot.application.AttachmentInput;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Validates image attachments in stages; every stage re-checks, trusting none before it. */
public final class AttachmentPolicy {

  private static final Set<String> ALLOWED_HOSTS =
      Set.of("cdn.discordapp.com", "media.discordapp.net");

  private AttachmentPolicy() {
  }

  public sealed interface Result permits Result.Accepted, Result.Rejected {
    record Accepted() implements Result {
    }

    record Rejected(Reason reason) implements Result {
    }
  }

  public enum Reason {
    INVALID_TYPE,
    TOO_LARGE,
    TOO_MANY_PIXELS
  }

  public static Result validateReported(AttachmentInput in, long maxBytes, long maxPixels) {
    if (in == null) {
      return new Result.Rejected(Reason.INVALID_TYPE);
    }
    if (!isSupportedContentType(in.contentType())) {
      return new Result.Rejected(Reason.INVALID_TYPE);
    }
    if (in.size() <= 0 || in.size() > maxBytes) {
      return new Result.Rejected(Reason.TOO_LARGE);
    }
    if (in.width() > 0 && in.height() > 0
        && (long) in.width() * in.height() > maxPixels) {
      return new Result.Rejected(Reason.TOO_MANY_PIXELS);
    }
    return new Result.Accepted();
  }

  public static Result validateBytes(byte[] bytes, long maxPixels) {
    if (bytes == null || bytes.length < 3 || !hasImageMagic(bytes)) {
      return new Result.Rejected(Reason.INVALID_TYPE);
    }
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        return new Result.Rejected(Reason.INVALID_TYPE);
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels > maxPixels) {
          return new Result.Rejected(Reason.TOO_MANY_PIXELS);
        }
      } finally {
        reader.dispose();
      }
    } catch (Exception e) {
      return new Result.Rejected(Reason.INVALID_TYPE);
    }
    return new Result.Accepted();
  }

  public static boolean isAllowedDownloadUrl(URI uri) {
    if (uri == null) {
      return false;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return false;
    }
    if (uri.getUserInfo() != null) {
      return false;
    }
    int port = uri.getPort();
    if (port != -1 && port != 443) {
      return false;
    }
    String host = uri.getHost();
    if (host == null) {
      return false;
    }
    return ALLOWED_HOSTS.contains(host.toLowerCase());
  }

  private static boolean isSupportedContentType(String contentType) {
    if (contentType == null) {
      return false;
    }
    String base = contentType.split(";")[0].strip().toLowerCase();
    return base.equals("image/png") || base.equals("image/jpeg") || base.equals("image/webp");
  }

  private static boolean hasImageMagic(byte[] bytes) {
    if (bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4E
        && bytes[3] == 0x47
        && (bytes[4] & 0xFF) == 0x0D
        && (bytes[5] & 0xFF) == 0x0A
        && (bytes[6] & 0xFF) == 0x1A
        && (bytes[7] & 0xFF) == 0x0A) {
      return true;
    }
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF) {
      return true;
    }
    return isWebpMagic(bytes);
  }

  private static boolean isWebpMagic(byte[] bytes) {
    if (bytes.length < 12) {
      return false;
    }
    return bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'E'
        && bytes[10] == 'B'
        && bytes[11] == 'P';
  }
}
