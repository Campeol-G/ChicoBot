package dev.chico.bot.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TsvParserTest {

  private static final String TSV =
      "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
          + "1\t1\t0\t0\t0\t0\t0\t0\t1000\t2000\t-1\t\n"
          + "2\t1\t1\t0\t0\t0\t10\t20\t980\t300\t-1\t\n"
          + "3\t1\t1\t1\t0\t0\t10\t20\t980\t90\t-1\t\n"
          + "4\t1\t1\t1\t1\t0\t10\t20\t400\t60\t-1\t\n"
          + "5\t1\t1\t1\t1\t1\t10\t20\t150\t60\t96.5\tAzul\n"
          + "5\t1\t1\t1\t1\t2\t170\t20\t120\t60\t93.2\tda\n"
          + "5\t1\t1\t1\t2\t3\t10\t100\t200\t60\t88.0\tCor\n"
          + "5\t1\t1\t1\t2\t4\t220\t100\t0\t0\t-1\t\n";

  @Test
  void parsesWordColumns() {
    List<OcrWord> words = TsvParser.parse(TSV);

    assertEquals(3, words.size());
    OcrWord first = words.get(0);
    assertEquals("Azul", first.text());
    assertEquals(10, first.left());
    assertEquals(20, first.top());
    assertEquals(150, first.width());
    assertEquals(60, first.height());
    assertEquals(96.5f, first.confidence(), 0.001);
    assertEquals(1, first.line());
    assertEquals(2, words.get(2).line());
  }

  @Test
  void headerAndNonWordRowsSkipped() {
    List<OcrWord> words = TsvParser.parse(TSV);

    assertTrue(words.stream().noneMatch(word -> word.text().isEmpty()));
    assertTrue(words.stream().allMatch(word -> word.confidence() >= 0));
  }

  @Test
  void nanAndInfiniteConfidenceDropped() {
    String rows =
        "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
            + "5\t1\t1\t1\t1\t1\t10\t20\t150\t60\tNaN\tGhost\n"
            + "5\t1\t1\t1\t1\t2\t170\t20\t120\t60\tInfinity\tPhantom\n"
            + "5\t1\t1\t1\t1\t3\t300\t20\t120\t60\t95.0\tReal\n";
    List<OcrWord> words = TsvParser.parse(rows);

    assertEquals(List.of("Real"), words.stream().map(OcrWord::text).toList());
  }
}
