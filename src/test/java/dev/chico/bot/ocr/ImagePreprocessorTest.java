package dev.chico.bot.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImagePreprocessorTest {

  private static byte[] pngOf(BufferedImage image) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  @Test
  void darkImageComesOutGrayscaleAndInverted() throws Exception {
    BufferedImage dark = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = dark.createGraphics();
    graphics.setColor(new Color(10, 10, 10));
    graphics.fillRect(0, 0, 100, 100);
    graphics.setColor(Color.WHITE);
    graphics.drawString("ab", 20, 50);
    graphics.dispose();

    byte[] processed = new ImagePreprocessor(4096L * 4096L).preprocess(pngOf(dark));

    BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed));
    int rgb = result.getRGB(5, 5);
    int red = (rgb >> 16) & 0xFF;
    int green = (rgb >> 8) & 0xFF;
    int blue = rgb & 0xFF;
    assertEquals(red, green, "output is grayscale");
    assertEquals(green, blue, "output is grayscale");
    assertTrue(red > 200, "dark background is inverted, got " + red);
  }

  @Test
  void forgedHeaderDimensionsRejectedBeforeDecoding() {
    byte[] forged = forgedPng(20000, 20000);

    OcrException failure =
        assertThrows(
            OcrException.class,
            () -> new ImagePreprocessor(4096L * 4096L).preprocess(forged));

    assertTrue(failure.getMessage().contains("20000"), failure.getMessage());
  }

  @Test
  void nonImageRejected() {
    byte[] text = "this is not an image".getBytes();

    assertThrows(
        OcrException.class, () -> new ImagePreprocessor(4096L * 4096L).preprocess(text));
  }

  @Test
  void outputDecodesBackToAnImage() throws Exception {
    BufferedImage plain = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = plain.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, 120, 40);
    graphics.setColor(Color.BLACK);
    graphics.drawString("hello", 10, 25);
    graphics.dispose();

    byte[] processed = new ImagePreprocessor(4096L * 4096L).preprocess(pngOf(plain));

    BufferedImage result = ImageIO.read(new ByteArrayInputStream(processed));
    assertTrue(result.getWidth() > 0 && result.getHeight() > 0);
  }

  private static byte[] forgedPng(int width, int height) {
    ByteBuffer buffer = ByteBuffer.allocate(8 + 4 + 4 + 13 + 4).order(ByteOrder.BIG_ENDIAN);
    buffer.put(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    buffer.putInt(13);
    buffer.put(new byte[] {'I', 'H', 'D', 'R'});
    buffer.putInt(width);
    buffer.putInt(height);
    buffer.put(new byte[] {8, 2, 0, 0, 0});
    buffer.putInt(0);
    return buffer.array();
  }
}
