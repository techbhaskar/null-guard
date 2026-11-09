package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaAdapterTest {
  @TempDir Path root;

  @Test void originalFilesKeepTheirLocationsAndNestedProjectsRemainSeparate() throws Exception {
    Path parent = Files.createDirectories(root.resolve("src/main/java")).resolve("Parent.java");
    Path child = Files.createDirectories(root.resolve("child/src/main/java")).resolve("Child.java");
    String parentSource = "class Parent { int bad(String value) { return value.length(); } }";
    Files.writeString(parent, parentSource);
    Files.writeString(child, "class Child { int bad(String value) { return value.length(); } }");
    Files.writeString(root.resolve("Ignored.java"), "class Ignored { broken syntax");
    var config = com.nullguard.bootstrap.NullGuardConfig.defaults().osvOnlineLookup(false)
        .selectedSourceFiles(List.of(child)).build();
    var analyzer = new JavaLanguageAnalyzer(config);
    var result = analyzer.analyze(new ProjectSpec("parent", root, List.of("java"), List.of("src/main/java/Parent.java")));
    assertEquals(1, result.path("legacyCompleteness").path("sourceFilesDiscovered").asInt());
    assertEquals(1, result.path("legacyCompleteness").path("sourceFilesParsed").asInt());
    assertFalse(result.path("findings").isEmpty());
    for (var finding : result.path("findings")) {
      assertTrue(finding.path("methodId").asText().startsWith("Parent#"));
      assertEquals("src/main/java/Parent.java", finding.path("location").path("path").asText());
    }
    var childResult = analyzer.analyze(new ProjectSpec("child", root.resolve("child"), List.of("java"), List.of("src/main/java/Child.java")));
    assertEquals(1, childResult.path("legacyCompleteness").path("sourceFilesDiscovered").asInt());
    assertTrue(childResult.path("findings").toString().contains("Child#"));
    assertEquals(List.of(child), config.getSelectedSourceFiles(), "Project selections must not mutate shared configuration");
    assertEquals(parentSource, Files.readString(parent));
    var report = Protocol.JSON.createObjectNode();
    result.put("root", root.toString());
    report.putArray("projects").add(result);
    String sarif = new MultiLanguageReports().sarif(report).toString();
    assertTrue(sarif.contains(parent.toUri().toASCIIString()), sarif);
    assertFalse(sarif.contains("nullguard-java-source-"));
  }

  @Test void emptyAndInvalidSelectionsCannotFallBackToScanningTheWholeProject() throws Exception {
    Files.writeString(root.resolve("Unselected.java"), "class Unselected { int bad(String s) { return s.length(); } }");
    var analyzer = new JavaLanguageAnalyzer(com.nullguard.bootstrap.NullGuardConfig.defaults().osvOnlineLookup(false).build());
    var empty = analyzer.analyze(new ProjectSpec("empty", root, List.of("java"), List.of()));
    assertEquals("failed", empty.path("status").asText());
    assertTrue(empty.path("findings").isEmpty());
    assertEquals(0, empty.path("coverage").path("java").path("discoveredFiles").asInt());
    assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(new ProjectSpec("bad", root, List.of("java"), List.of("missing.java"))));
    assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(new ProjectSpec("escape", root, List.of("java"), List.of("../outside.java"))));
  }

  @Test
  void javaAdapterRetainsLegacyRuleIdsAndAvailableScore() throws Exception {
    Files.writeString(
        root.resolve("Example.java"),
        "class Example { int bad(String value) { return value.length(); } }");
    var project = new ProjectSpec("java", root, List.of("java"), List.of("Example.java"));
    var result =
        new JavaLanguageAnalyzer(com.nullguard.bootstrap.NullGuardConfig.defaults().osvOnlineLookup(false).build())
            .analyze(project);
    assertEquals(1, result.path("coverage").path("java").path("analyzedFiles").asInt());
    assertTrue(result.path("capabilities").path("stabilityScore").asBoolean());
    assertFalse(result.path("findings").isEmpty());
    assertTrue(result.path("findings").get(0).path("ruleId").asText().startsWith("NG"));
    result.put("root", root.toString());
    var report = Protocol.JSON.createObjectNode();
    report.putArray("projects").add(result);
    var sarif = new MultiLanguageReports().sarif(report);
    assertFalse(sarif.path("runs").get(0).path("results").isEmpty());
  }
}
