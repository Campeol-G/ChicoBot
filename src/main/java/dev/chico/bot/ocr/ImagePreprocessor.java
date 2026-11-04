package dev.chico.bot.ocr;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Normalizes screenshots for Tesseract: size, grayscale, dark-mode inversion. */
public final class ImagePreprocessor {

  private static final int DOWNSCALE_LIMIT = 3000;
  private static final int UPSCALE_LIMIT = 800;
  private static final int DARK_MEAN = 128;

  private final long maxPixels;
  private final boolean binarize;

  public ImagePreprocessor(long maxPixels) {
    this(maxPixels, false);
  }

  public ImagePreprocessor(long maxPixels, boolean binarize) {
    this.maxPixels = maxPixels;
    this.binarize = binarize;
    ImageIO.setUseCache(false);
  }

  public byte[] preprocess(byte[] input) throws OcrException {
    if (input == null || input.length == 0) {
      throw new OcrException("Empty image");
    }
    int[] dimensions = readDimensions(input);
    long pixels = (long) dimensions[0] * dimensions[1];
    if (pixels > maxPixels) {
      throw new OcrException(
          "Image too large: " + dimensions[0] + "x" + dimensions[1] + " pixels");
    }
    BufferedImage decoded = decode(input);
    BufferedImage flat = flattenAlpha(decoded);
    BufferedImage scaled = rescale(flat);
    BufferedImage gray = grayscale(scaled);
    BufferedImage inverted = invertIfDark(gray);
    BufferedImage finalImage = binarize ? otsu(inverted) : inverted;
    return encode(finalImage);
  }

  private static int[] readDimensions(byte[] input) throws OcrException {
    try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        throw new OcrException("Unsupported image format");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        return new int[] {reader.getWidth(0), reader.getHeight(0)};
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new OcrException("Cannot read image dimensions", e);
    }
  }

  private static BufferedImage decode(byte[] input) throws OcrException {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(input));
      if (image == null) {
        throw new OcrException("Unsupported image format");
      }
      return image;
    } catch (IOException e) {
      throw new OcrException("Cannot decode image", e);
    }
  }

  private static BufferedImage flattenAlpha(BufferedImage image) {
    if (!image.getColorModel().hasAlpha()) {
      return image;
    }
    BufferedImage flat =
        new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = flat.createGraphics();
    try {
      graphics.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
    } finally {
      graphics.dispose();
    }
    return flat;
  }

  private static BufferedImage rescale(BufferedImage image) {
    int longest = Math.max(image.getWidth(), image.getHeight());
    double factor = 1.0;
    if (longest > DOWNSCALE_LIMIT) {
      factor = (double) DOWNSCALE_LIMIT / longest;
    } else if (longest < UPSCALE_LIMIT) {
      factor = 2.0;
    }
    if (factor == 1.0) {
      return image;
    }
    int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
    int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
    BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = scaled.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(image, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return scaled;
  }

  private static BufferedImage grayscale(BufferedImage image) {
    BufferedImage gray =
        new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
    Graphics2D graphics = gray.createGraphics();
    try {
      graphics.drawImage(image, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return gray;
  }

  private static BufferedImage invertIfDark(BufferedImage gray) {
    long sum = 0;
    int width = gray.getWidth();
    int height = gray.getHeight();
    int[] row = new int[width];
    for (int y = 0; y < height; y++) {
      gray.getRaster().getPixels(0, y, width, 1, row);
      for (int value : row) {
        sum += value;
      }
    }
    double mean = (double) sum / (width * height);
    if (mean >= DARK_MEAN) {
      return gray;
    }
    BufferedImage inverted =
        new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    int[] out = new int[width];
    for (int y = 0; y < height; y++) {
      gray.getRaster().getPixels(0, y, width, 1, row);
      for (int x = 0; x < width; x++) {
        out[x] = 255 - row[x];
      }
      inverted.getRaster().setPixels(0, y, width, 1, out);
    }
    return inverted;
  }

  private static BufferedImage otsu(BufferedImage gray) {
    int width = gray.getWidth();
    int height = gray.getHeight();
    int total = width * height;
    int[] histogram = new int[256];
    int[] row = new int[width];
    for (int y = 0; y < height; y++) {
      gray.getRaster().getPixels(0, y, width, 1, row);
      for (int value : row) {
        histogram[value]++;
      }
    }
    long sum = 0;
    for (int i = 0; i < 256; i++) {
      sum += (long) i * histogram[i];
    }
    long sumBackground = 0;
    int weightBackground = 0;
    double bestVariance = -1;
    int threshold = 128;
    for (int i = 0; i < 256; i++) {
      weightBackground += histogram[i];
      if (weightBackground == 0) {
        continue;
      }
      int weightForeground = total - weightBackground;
      if (weightForeground == 0) {
        break;
      }
      sumBackground += (long) i * histogram[i];
      double meanBackground = (double) sumBackground / weightBackground;
      double meanForeground = (double) (sum - sumBackground) / weightForeground;
      double variance =
          (double) weightBackground * weightForeground * Math.pow(meanBackground - meanForeground, 2);
      if (variance > bestVariance) {
        bestVariance = variance;
        threshold = i;
      }
    }
    BufferedImage binary = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    int[] out = new int[width];
    for (int y = 0; y < height; y++) {
      gray.getRaster().getPixels(0, y, width, 1, row);
      for (int x = 0; x < width; x++) {
        out[x] = row[x] > threshold ? 255 : 0;
      }
      binary.getRaster().setPixels(0, y, width, 1, out);
    }
    return binary;
  }

  private static byte[] encode(BufferedImage image) throws OcrException {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!ImageIO.write(image, "png", out)) {
        throw new OcrException("No PNG writer available");
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new OcrException("Cannot encode image", e);
    }
  }
}
