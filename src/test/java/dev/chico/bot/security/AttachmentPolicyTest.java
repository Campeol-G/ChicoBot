package dev.chico.bot.security;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.chico.bot.application.AttachmentInput;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AttachmentPolicyTest {

  private static byte[] pngBytes(int width, int height) throws Exception {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private static byte[] jpegBytes() throws Exception {
    BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", out);
    return out.toByteArray();
  }

  private static byte[] forgedPngHeader(int width, int height) {
    byte[] bytes = new byte[8 + 8 + 13 + 4];
    byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(signature, 0, bytes, 0, 8);
    bytes[8] = 0;
    bytes[9] = 0;
    bytes[10] = 0;
    bytes[11] = 13;
    bytes[12] = 'I';
    bytes[13] = 'H';
    bytes[14] = 'D';
    bytes[15] = 'R';
    bytes[16] = (byte) (width >> 24);
    bytes[17] = (byte) (width >> 16);
    bytes[18] = (byte) (width >> 8);
    bytes[19] = (byte) width;
    bytes[20] = (byte) (height >> 24);
    bytes[21] = (byte) (height >> 16);
    bytes[22] = (byte) (height >> 8);
    bytes[23] = (byte) height;
    return bytes;
  }

  private static AttachmentInput input(String contentType, long size, int width, int height)
      throws Exception {
    return new AttachmentInput(
        new URI("https://cdn.discordapp.com/attachments/1/2/image.png"),
        contentType,
        size,
        width,
        height);
  }

  @Test
  void realPngBytesAccepted() throws Exception {
    var result = AttachmentPolicy.validateBytes(pngBytes(4, 4), 4096L * 4096L);
    assertInstanceOf(AttachmentPolicy.Result.Accepted.class, result);
  }

  @Test
  void realJpegBytesAccepted() throws Exception {
    var result = AttachmentPolicy.validateBytes(jpegBytes(), 4096L * 4096L);
    assertInstanceOf(AttachmentPolicy.Result.Accepted.class, result);
  }

  @Test
  void garbageBytesRejected() {
    var result = AttachmentPolicy.validateBytes(new byte[] {0, 1, 2, 3}, 4096L * 4096L);
    assertInstanceOf(AttachmentPolicy.Result.Rejected.class, result);
  }

  @Test
  void renamedTextFileRejected() {
    var result =
        AttachmentPolicy.validateBytes("hello world".getBytes(), 4096L * 4096L);
    assertInstanceOf(AttachmentPolicy.Result.Rejected.class, result);
  }

  @Test
  void oversizedReportedSizeRejected() throws Exception {
    var result = input("image/png", 8_388_608L + 1, 100, 100);
    var checked = AttachmentPolicy.validateReported(result, 8_388_608L, 4096L * 4096L);
    assertInstanceOf(AttachmentPolicy.Result.Rejected.class, checked);
  }

  @Test
  void forgedHugeHeaderRejectedBeforeDecode() {
    var result = AttachmentPolicy.validateBytes(forgedPngHeader(20000, 20000), 4096L * 4096L);
    assertInstanceOf(AttachmentPolicy.Result.Rejected.class, result);
  }

  @Test
  void wrongContentTypeRejected() throws Exception {
    var checked =
        AttachmentPolicy.validateReported(input("text/plain", 100, 10, 10), 8_388_608L,
            4096L * 4096L);
    assertInstanceOf(AttachmentPolicy.Result.Rejected.class, checked);
  }

  @Test
  void allowedHostsAccepted() throws Exception {
    assertTrue(
        AttachmentPolicy.isAllowedDownloadUrl(
            new URI("https://cdn.discordapp.com/attachments/1/2/image.png")));
    assertTrue(
        AttachmentPolicy.isAllowedDownloadUrl(
            new URI("https://media.discordapp.net/attachments/1/2/image.png")));
  }

  @Test
  void hostileUrlsRejected() throws Exception {
    assertTrue(!AttachmentPolicy.isAllowedDownloadUrl(
        new URI("https://cdn.discordapp.com.evil.test/attachments/1/2/x.png")));
    assertTrue(!AttachmentPolicy.isAllowedDownloadUrl(
        new URI("http://cdn.discordapp.com/attachments/1/2/x.png")));
    assertTrue(!AttachmentPolicy.isAllowedDownloadUrl(
        new URI("https://user@cdn.discordapp.com/attachments/1/2/x.png")));
    assertTrue(!AttachmentPolicy.isAllowedDownloadUrl(
        new URI("https://cdn.discordapp.com:8443/attachments/1/2/x.png")));
  }
}
