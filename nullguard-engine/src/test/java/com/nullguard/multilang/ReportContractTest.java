package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportContractTest {
  @TempDir Path root;

  ProjectSpec project() {
    return new ProjectSpec("p", root, List.of("python"), List.of("app.py"));
  }

  @Test
  void findingsPreserveEvidenceAndRelatedLocationsInSarif() {
    var p = Protocol.failed(project(), "missing reference");
    p.put("root", root.toString());
    var f =
        p.withArray("findings")
            .addObject()
            .put("ruleId", "NG-PY-001")
            .put("language", "python")
            .put("severity", "warning")
            .put("confidence", "experimental")
            .put("message", "Possible None access");
    f.putArray("evidence").add("Origin: optional parameter");
    f.putObject("location").put("path", "app.py").put("startLine", 4).put("startColumn", 2);
    var related = f.putArray("relatedLocations");
    related.addObject().put("path", "app.py").put("startLine", 1).put("startColumn", 1);
    related.addObject().put("path", "app.py").put("startLine", 0);
    var report = Protocol.JSON.createObjectNode();
    report.putArray("projects").add(p);
    var result =
        new MultiLanguageReports().sarif(report).path("runs").get(0).path("results").get(0);
    assertEquals(1, result.path("relatedLocations").size());
    assertEquals(
        "Origin: optional parameter", result.path("properties").path("evidence").get(0).asText());
    assertTrue(
        result
            .path("locations")
            .get(0)
            .path("physicalLocation")
            .path("artifactLocation")
            .path("uri")
            .asText()
            .endsWith("app.py"));
  }

  @Test
  void protocolRejectsMissingCapabilitiesMalformedCoverageAndEscapingEvidence() {
    var p = project();
    var response = Protocol.failed(p, "parse failed");
    response.remove("dependencies");
    assertThrows(Exception.class, () -> Protocol.validate(response, p));
    response.putArray("dependencies");
    response.withObject("/coverage/python").remove("budgetExhausted");
    assertThrows(Exception.class, () -> Protocol.validate(response, p));
    response.withObject("/coverage/python").put("budgetExhausted", false);
    var f =
        response
            .withArray("findings")
            .addObject()
            .put("ruleId", "NG-PY-001")
            .put("language", "python")
            .put("severity", "warning")
            .put("confidence", "experimental")
            .put("message", "risk");
    f.putArray("evidence");
    f.putObject("location").put("path", "app.py").put("startLine", 1).put("startColumn", 1);
    f.putArray("relatedLocations")
        .addObject()
        .put("path", "../escape.py")
        .put("startLine", 1)
        .put("startColumn", 1);
    assertThrows(Exception.class, () -> Protocol.validate(response, p));
    f.putArray("relatedLocations");
    assertDoesNotThrow(() -> Protocol.validate(response, p));
    f.put("confidence", "certain");
    assertThrows(Exception.class, () -> Protocol.validate(response, p));
  }

  @Test
  void platformMappingDoesNotSilentlySelectAnUnsupportedNativeBinary() {
    String os = System.getProperty("os.name"), arch = System.getProperty("os.arch");
    try {
      System.setProperty("os.name", "Windows 11");
      System.setProperty("os.arch", "amd64");
      assertEquals("windows-x64", PackManager.platform());
      System.setProperty("os.name", "Linux");
      System.setProperty("os.arch", "aarch64");
      assertEquals("linux-arm64", PackManager.platform());
      System.setProperty("os.arch", "unsupported");
      assertThrows(Exception.class, PackManager::platform);
      System.setProperty("os.name", "Plan9");
      assertThrows(Exception.class, PackManager::platform);
    } finally {
      System.setProperty("os.name", os);
      System.setProperty("os.arch", arch);
    }
  }
}
