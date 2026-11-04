package dev.chico.bot.parser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NoiseFilterTest {

  @Test
  void durationsAreNoise() {
    assertTrue(NoiseFilter.isNoise("3:45"));
    assertTrue(NoiseFilter.isNoise("12:28"));
  }

  @Test
  void interfaceWordsAreNoise() {
    assertTrue(NoiseFilter.isNoise("Adicionar"));
    assertTrue(NoiseFilter.isNoise("Add"));
    assertTrue(NoiseFilter.isNoise("Baixado"));
    assertTrue(NoiseFilter.isNoise("Downloaded"));
    assertTrue(NoiseFilter.isNoise("Aleatório"));
    assertTrue(NoiseFilter.isNoise("Shuffle"));
    assertTrue(NoiseFilter.isNoise("Title"));
    assertTrue(NoiseFilter.isNoise("Título"));
    assertTrue(NoiseFilter.isNoise("Album"));
    assertTrue(NoiseFilter.isNoise("Álbum"));
    assertTrue(NoiseFilter.isNoise("Date added"));
    assertTrue(NoiseFilter.isNoise("Adicionado em"));
  }

  @Test
  void playCountsAreNoise() {
    assertTrue(NoiseFilter.isNoise("Tocou 618 mil vezes"));
    assertTrue(NoiseFilter.isNoise("Tocou 81 mi vezes"));
    assertTrue(NoiseFilter.isNoise("1.2 mi de visualizações"));
    assertTrue(NoiseFilter.isNoise("1,234 views"));
  }

  @Test
  void datesAreNoise() {
    assertTrue(NoiseFilter.isNoise("há 2 dias"));
    assertTrue(NoiseFilter.isNoise("há 3 semanas"));
    assertTrue(NoiseFilter.isNoise("22 de set. de 2026"));
    assertTrue(NoiseFilter.isNoise("Dec 16, 2025"));
  }

  @Test
  void interfaceWordsInsideLineMarkHeaders() {
    assertTrue(NoiseFilter.containsInterfaceWord("t* título"));
    assertTrue(NoiseFilter.containsInterfaceWord("# Título ®"));
    assertTrue(NoiseFilter.containsInterfaceWord("Title Album Date added"));
    assertFalse(NoiseFilter.containsInterfaceWord("Feather"));
    assertFalse(NoiseFilter.containsInterfaceWord("Nujabes, Cise Starr"));
  }

  @Test
  void symbolsAndNumbersAreNoise() {
    assertTrue(NoiseFilter.isNoise("#"));
    assertTrue(NoiseFilter.isNoise("•"));
    assertTrue(NoiseFilter.isNoise("7"));
    assertTrue(NoiseFilter.isNoise("1."));
  }

  @Test
  void realTitlesAreNotNoise() {
    assertFalse(NoiseFilter.isNoise("Flor de Lis (Remasterizada 2026)"));
    assertFalse(NoiseFilter.isNoise("Nujabes, Cise Starr"));
    assertFalse(NoiseFilter.isNoise("Pink + White"));
  }
}
