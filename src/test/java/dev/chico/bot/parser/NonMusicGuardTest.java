package dev.chico.bot.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NonMusicGuardTest {

  private static ParsedTrack track(String title) {
    return new ParsedTrack("raw", title, "", 90.0);
  }

  private static ParsedTrack track(String title, String artist) {
    return new ParsedTrack("raw", title, artist, 90.0);
  }

  private static List<ParsedTrack> codePrint() {
    return List.of(
        track("$ sudo apt update"),
        track("sudo apt install openjdk-21-jdk"),
        track("--install-suggests --no-install-recommends"),
        track("Requirements"),
        track("Java 26 or newer"),
        track("mvn -q package -DskipTests"),
        track("public class Main {"),
        track("return value;"),
        track("java -jar target/chicos-bot.jar"));
  }

  @Test
  void codeScreenshotIsRejected() {
    NonMusicGuard.Result result = NonMusicGuard.evaluate(codePrint());

    assertEquals(NonMusicGuard.Verdict.REJECT, result.verdict());
    assertTrue(result.kept().isEmpty());
  }

  @Test
  void newsPrintIsRejected() {
    List<ParsedTrack> news = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      news.add(
          track(
              "Governo anuncia novo pacote de medidas economicas para o proximo "
                  + "trimestre, com foco em emprego e renda."));
    }

    assertEquals(NonMusicGuard.Verdict.REJECT, NonMusicGuard.evaluate(news).verdict());
  }

  @Test
  void mixedPrintWarnsAndKeepsMusic() {
    List<ParsedTrack> mixed = new ArrayList<>();
    mixed.add(track("$ docker compose up -d"));
    mixed.add(track("Requirements"));
    mixed.add(track("npm install --save-dev"));
    for (int i = 0; i < 7; i++) {
      mixed.add(track("Musica " + i, "Artista " + i));
    }

    NonMusicGuard.Result result = NonMusicGuard.evaluate(mixed);

    assertEquals(NonMusicGuard.Verdict.WARN, result.verdict());
    assertEquals(7, result.kept().size());
    assertEquals(3, result.dropped());
  }

  @Test
  void shortListAlwaysPasses() {
    List<ParsedTrack> shortList =
        List.of(track("$ sudo apt update"), track("Requirements"));

    NonMusicGuard.Result result = NonMusicGuard.evaluate(shortList);

    assertEquals(NonMusicGuard.Verdict.NONE, result.verdict());
    assertEquals(2, result.kept().size());
  }

  @Test
  void singleWordTitlesDoNotTrigger() {
    assertFalse(NonMusicGuard.isCodeLine("Sudo"));
    assertFalse(NonMusicGuard.isCodeLine("Import"));
    assertFalse(NonMusicGuard.isCodeLine("Echo"));
    assertFalse(NonMusicGuard.isCodeLine("From the Beginning"));
  }

  @Test
  void goldenPlaylistTitlesStayClean() {
    List<ParsedTrack> playlist =
        List.of(
            track("Cega Eles Vovo", "MC"),
            track("No Piscar dos Olhos", "Fundo de Quintal"),
            track("Billie Jean", "Michael Jackson"),
            track("Thriller", "Michael Jackson"),
            track("Like a Prayer", "Madonna"),
            track("Tempo Perdido", "Legiao Urbana"));

    NonMusicGuard.Result result = NonMusicGuard.evaluate(playlist);

    assertEquals(NonMusicGuard.Verdict.NONE, result.verdict());
    assertEquals(6, result.kept().size());
  }
}
