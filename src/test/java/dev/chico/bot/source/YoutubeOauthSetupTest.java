package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YoutubeOauthSetupTest {

  @Test
  void tokenFileIsOwnerOnlyAndContainsTheRefreshToken(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("token");

    YoutubeOauthSetup.writeToken(file, "refresh-value");

    assertEquals("YT_REFRESH_TOKEN=refresh-value\n", Files.readString(file));
    assertEquals(
        "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }
}
