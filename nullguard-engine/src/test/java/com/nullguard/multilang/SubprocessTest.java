package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubprocessTest {
  @TempDir Path root;

  PackManager fake(String mode) throws Exception {
    PackManager packs = new PackManager(root.resolve("packs"));
    Files.createDirectories(packs.directory("python"));
    ObjectNode manifest = Protocol.JSON.createObjectNode();
    manifest.put("id", "python");
    manifest.put("version", Protocol.ANALYZER_VERSION);
    manifest.put("protocolVersion", Protocol.VERSION);
    ArrayNode command = manifest.putArray("command");
    command
        .add(
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString())
        .add("-cp")
        .add(System.getProperty("java.class.path"))
        .add(FakePack.class.getName())
        .add(mode);
    Protocol.JSON.writeValue(packs.directory("python").resolve("pack.json").toFile(), manifest);
    return packs;
  }

  ProjectSpec project() {
    return new ProjectSpec("fixture", root, List.of("python"), List.of("test.py"));
  }

  @Test
  void validPackResponseSurvivesWithoutImportingProject() throws Exception {
    var result =
        new SubprocessAnalyzer(fake("valid"), "python", Duration.ofSeconds(10)).analyze(project());
    assertEquals("complete", result.path("status").asText());
  }

  @Test
  void malformedProtocolAndTrailingDataAreRejected() throws Exception {
    for (String mode : List.of("version", "trailing", "malformed", "outside")) {
      var packs = fake(mode);
      assertThrows(
          Exception.class,
          () -> new SubprocessAnalyzer(packs, "python", Duration.ofSeconds(10)).analyze(project()),
          mode);
    }
  }

  @Test
  void missingRuntimeIsAnAnalysisFailure() throws Exception {
    var packs = fake("valid");
    var manifest = (ObjectNode) packs.manifest("python");
    manifest.putArray("command").add(root.resolve("missing-runtime").toString());
    Protocol.JSON.writeValue(packs.directory("python").resolve("pack.json").toFile(), manifest);
    assertThrows(
        java.io.IOException.class,
        () -> new SubprocessAnalyzer(packs, "python", Duration.ofSeconds(1)).analyze(project()));
  }

  @Test
  void aFailedAdapterDoesNotDiscardAnotherProjectsSuccessfulResult() throws Exception {
    Files.writeString(root.resolve("test.py"), "value=None");
    Files.writeString(root.resolve("broken.ts"), "const value=null;");
    var report =
        new MultiLanguageRunner(fake("valid"), Duration.ofSeconds(10))
            .run(
                root,
                Set.of(Language.PYTHON, Language.TYPESCRIPT),
                com.nullguard.bootstrap.NullGuardConfig.defaults().build(),
                null,
                false);
    assertEquals("partial", report.path("status").asText());
    assertEquals(2, report.path("projects").size());
    assertEquals(
        1,
        java.util.stream.StreamSupport.stream(report.path("projects").spliterator(), false)
            .filter(p -> p.path("status").asText().equals("complete"))
            .count());
    assertEquals(
        1,
        java.util.stream.StreamSupport.stream(report.path("projects").spliterator(), false)
            .filter(p -> p.path("status").asText().equals("failed"))
            .count());
  }

  @Test
  void timeoutTerminatesPackInsteadOfHanging() throws Exception {
    var packs = fake("sleep");
    long start = System.nanoTime();
    assertThrows(
        Exception.class,
        () -> new SubprocessAnalyzer(packs, "python", Duration.ofMillis(150)).analyze(project()));
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
  }

  @Test
  void stderrLimitIsCheckedEvenWhenPackAlreadyExited() throws Exception {
    var packs = fake("stderr");
    assertThrows(
        Exception.class,
        () -> new SubprocessAnalyzer(packs, "python", Duration.ofSeconds(10)).analyze(project()));
  }

  @Test
  void htmlEscapesPayloadAndSarifHasFailureNotification() throws Exception {
    var p = project();
    var report = Protocol.JSON.createObjectNode();
    report.put("schemaVersion", "2.0");
    report.put("status", "partial");
    var failed = Protocol.failed(p, "</script><script>alert('x')</script>");
    failed.put("root", root.toString());
    report.putArray("projects").add(failed);
    report.putArray("diagnostics");
    new MultiLanguageReports().write(report, root.resolve("out"));
    String html = Files.readString(root.resolve("out/nullguard-dashboard-latest.html"));
    assertFalse(html.contains("</script><script>alert"));
    assertFalse(
        new MultiLanguageReports()
            .sarif(report)
            .path("runs")
            .get(0)
            .path("invocations")
            .get(0)
            .path("executionSuccessful")
            .asBoolean());
  }

  public static class FakePack {
    public static void main(String[] args) throws Exception {
      String mode = args[0];
      if (mode.equals("sleep")) {
        Thread.sleep(15000);
        return;
      }
      if (mode.equals("stderr")) {
        System.err.print("x".repeat(9 * 1024 * 1024));
        return;
      }
      var request = Protocol.JSON.readTree(System.in);
      if (mode.equals("malformed")) {
        System.out.print("not json");
        return;
      }
      var p =
          new ProjectSpec(
              request.path("project").asText(),
              Path.of(request.path("root").asText()),
              List.of("python"),
              List.of("test.py"));
      var result = Protocol.failed(p, "test");
      result.put("status", "complete");
      var coverage = result.withObject("/coverage/python");
      coverage.put("analyzedFiles", 1);
      coverage.put("failedFiles", 0);
      result.putArray("diagnostics");
      if (mode.equals("version")) result.put("protocolVersion", "future");
      if (mode.equals("outside"))
        result
            .withArray("findings")
            .addObject()
            .put("ruleId", "NG-PY-001")
            .put("language", "python")
            .put("severity", "warning")
            .put("confidence", "experimental")
            .put("message", "test")
            .putArray("evidence");
      System.out.print(result);
      if (mode.equals("trailing")) System.out.print(" {}");
    }
  }
}
