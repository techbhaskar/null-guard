package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PackManagerTest {
  @TempDir Path root;

  Path fixture() throws Exception {
    Path zip = root.resolve("fixture.zip");
    for (String suffix : new String[] {"", ".asc", ".sha256"})
      try (var in = getClass().getResourceAsStream("/multilang/fixture.zip" + suffix)) {
        assertNotNull(in);
        Files.copy(in, Path.of(zip + suffix));
      }
    return zip;
  }

  boolean gpgAvailable() {
    try {
      return new ProcessBuilder("gpgv", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void signedArchiveInstallsOnlyAfterVerificationAndCanBeRemoved() throws Exception {
    Assumptions.assumeTrue(gpgAvailable(), "GPGV required for signed installer test");
    var packs = new PackManager(root.resolve("home"));
    Path zip = fixture();
    packs.install("python", zip);
    assertEquals("python", packs.manifest("python").path("id").asText());
    assertTrue(Files.isRegularFile(packs.directory("python").resolve("fixture.py")));
    assertThrows(IllegalArgumentException.class, () -> packs.install("python", zip));
    packs.remove("python");
    assertFalse(Files.exists(packs.directory("python")));
  }

  @Test
  void tamperedChecksumIsRejectedBeforeExtraction() throws Exception {
    Path zip = fixture();
    Files.writeString(Path.of(zip + ".sha256"), "0".repeat(64));
    var packs = new PackManager(root.resolve("home"));
    assertThrows(Exception.class, () -> packs.install("python", zip));
    assertFalse(Files.exists(packs.directory("python")));
  }

  @Test
  void matchingChecksumDoesNotAllowInvalidSignature() throws Exception {
    Assumptions.assumeTrue(gpgAvailable());
    Path zip = fixture();
    Files.writeString(zip, "tampered");
    Files.writeString(
        Path.of(zip + ".sha256"),
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip))));
    var packs = new PackManager(root.resolve("home"));
    assertThrows(Exception.class, () -> packs.install("python", zip));
    assertFalse(Files.exists(packs.directory("python")));
  }

  @Test
  void invalidIdentifiersAndManifestVersionsAreRejected() throws Exception {
    var packs = new PackManager(root);
    assertThrows(IllegalArgumentException.class, () -> packs.directory("../escape"));
    Files.createDirectories(root.resolve("python"));
    Files.writeString(root.resolve("python/pack.json"), "{}");
    assertThrows(Exception.class, () -> packs.manifest("python"));
    assertNotNull(PackManager.standard().home());
    assertTrue(PackManager.platform().contains("-"));
  }
}
