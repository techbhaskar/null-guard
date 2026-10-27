package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaAdapterTest {
  @TempDir Path root;

  @Test
  void javaAdapterRetainsLegacyRuleIdsAndAvailableScore() throws Exception {
    Files.writeString(
        root.resolve("Example.java"),
        "class Example { int bad(String value) { return value.length(); } }");
    var project = new ProjectSpec("java", root, List.of("java"), List.of("Example.java"));
    var result =
        new JavaLanguageAnalyzer(com.nullguard.bootstrap.NullGuardConfig.defaults().build())
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
