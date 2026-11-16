package dev.chico.bot.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
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

  @Test
  void symlinkIsRefusedAndTargetUntouched(@TempDir Path dir) throws Exception {
    Path target = dir.resolve("target");
    Files.writeString(target, "keep");
    Path link = dir.resolve("link");
    Files.createSymbolicLink(link, target);

    assertThrows(IOException.class, () -> YoutubeOauthSetup.writeToken(link, "token"));
    assertEquals("keep", Files.readString(target));
  }
}
