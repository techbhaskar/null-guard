package com.nullguard.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiLanguageCliTest {
  @TempDir Path root;

  @Test
  void positionalRootIsParsedAndEmptyScanWritesFailureReport() throws Exception {
    Path output = root.resolve("reports");
    int exit =
        new NullGuardCliApplication()
            .run(new String[] {root.toString(), "--languages=auto", "--output=" + output});
    assertEquals(3, exit);
    assertTrue(
        Files.readString(output.resolve("nullguard-report-latest.json"))
            .contains("No selected-language"));
  }

  @Test
  void invalidOptionsAndPackOperationsHaveExplicitStatuses() {
    assertEquals(
        2,
        MultiLanguageCli.run(new String[] {root.toString(), "--languages=python", "--fail-build"}));
    assertEquals(0, MultiLanguageCli.run(new String[] {"packs", "list"}));
    assertEquals(2, MultiLanguageCli.run(new String[] {"packs", "install"}));
    assertEquals(
        2,
        MultiLanguageCli.run(new String[] {"packs", "install", "python", "go", "--from=no.zip"}));
    assertEquals(2, MultiLanguageCli.run(new String[] {root.toString(), "--languages=unknown"}));
    assertEquals(
        2,
        MultiLanguageCli.run(new String[] {root.toString(), "--languages=python", "--timeout=0"}));
    assertEquals(
        2,
        MultiLanguageCli.run(
            new String[] {root.toString(), root.toString(), "--languages=python"}));
  }

  @Test
  void previewAcceptsAnalysisOptionsAndRejectsMissingArchives() {
    assertEquals(
        3,
        MultiLanguageCli.run(
            new String[] {
              root.toString(),
              "--languages=python",
              "--timeout=10",
              "--classpath=" + root,
              "--cve-input=" + root.resolve("missing.json"),
              "--cve-online",
              "--output=" + root.resolve("out")
            }));
    assertEquals(
        3,
        MultiLanguageCli.run(
            new String[] {"packs", "install", "python", "--from=" + root.resolve("missing.zip")}));
    assertEquals(2, MultiLanguageCli.run(new String[] {"packs", "remove", "invalid"}));
    assertEquals(2, MultiLanguageCli.run(new String[] {"packs", "install", "--from=foo.zip"}));
  }

  @Test
  void legacyOptionsAreNotCapturedByPreviewRouter() {
    assertFalse(MultiLanguageCli.handles(new String[] {root.toString(), "--fail-build"}));
    assertTrue(MultiLanguageCli.handles(new String[] {"packs", "list"}));
  }

  @Test
  void multiLanguageJavaDefaultsOnlineAndSupportsOfflineOverride() throws Exception {
    Path source = Files.createDirectories(root.resolve("source"));
    Files.writeString(source.resolve("Safe.java"), "class Safe { int value() { return 1; } }");
    for (boolean offline : new boolean[] {false, true}) {
      Path output = root.resolve(offline ? "offline" : "default");
      var arguments = new java.util.ArrayList<>(java.util.List.of(
          source.toString(), "--languages=java", "--output=" + output));
      if (offline) arguments.add("--cve-offline");
      assertEquals(0, MultiLanguageCli.run(arguments.toArray(String[]::new)));
      var report = new com.fasterxml.jackson.databind.ObjectMapper()
          .readTree(output.resolve("nullguard-report-latest.json").toFile());
      assertEquals(!offline, report.path("projects").get(0).path("cveSummary").path("onlineRequested").asBoolean());
    }
  }
}
