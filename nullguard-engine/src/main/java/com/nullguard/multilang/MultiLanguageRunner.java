package com.nullguard.multilang;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.nullguard.bootstrap.NullGuardConfig;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class MultiLanguageRunner {
  private final PackManager packs;
  private final Duration timeout;

  public MultiLanguageRunner(PackManager packs, Duration timeout) {
    this.packs = packs;
    this.timeout = timeout;
  }

  public ObjectNode run(
      Path root, Set<Language> selected, NullGuardConfig javaConfig, Path cveInput, boolean online)
      throws Exception {
    ObjectNode report = Protocol.JSON.createObjectNode();
    report.put("schemaVersion", "2.0");
    report.put("analyzerVersion", Protocol.ANALYZER_VERSION);
    report.put("preview", true);
    report.put("status", "complete");
    report.putArray("projects");
    report.putArray("diagnostics");
    List<ProjectSpec> projects = new ProjectDiscovery().discover(root, selected);
    if (projects.isEmpty()) {
      report.put("status", "failed");
      report
          .withArray("diagnostics")
          .add("No selected-language source files or project manifests were discovered");
    }
    for (ProjectSpec p : projects) {
      String pack = Language.parse(p.languages().getFirst()).pack;
      ObjectNode result;
      try {
        if (p.files().isEmpty())
          throw new IllegalStateException("No selected-language source files in this project");
        LanguageAnalyzer analyzer =
            pack.equals("java")
                ? new JavaLanguageAnalyzer(javaConfig)
                : new SubprocessAnalyzer(packs, pack, timeout);
        result = analyzer.analyze(p);
      } catch (Exception e) {
        result = Protocol.failed(p, e.getClass().getSimpleName() + ": " + e.getMessage());
      }
      result.put("root", p.root().toString());
      if (!pack.equals("java")) {
        try {
          var deps = new DependencyDiscovery().discover(p);
          result.set("dependencies", Protocol.JSON.valueToTree(deps.dependencies()));
          deps.diagnostics().forEach(result.withArray("diagnostics")::add);
          for (String l : p.languages()) {
            ObjectNode coverage = (ObjectNode) result.path("coverage").path(l);
            ArrayNode gaps =
                coverage.has("missingDependencies") && coverage.get("missingDependencies").isArray()
                    ? (ArrayNode) coverage.get("missingDependencies")
                    : coverage.putArray("missingDependencies");
            deps.missing().forEach(gaps::add);
          }
          if (!deps.missing().isEmpty() && result.path("status").asText().equals("complete"))
            result.put("status", "partial");
          try {
            new AdvisoryMatcher().enrich(result, cveInput, online);
          } catch (Exception e) {
            result.put("status", "partial");
            result.withArray("diagnostics").add("Advisory matching incomplete: " + e.getMessage());
          }
        } catch (Exception e) {
          result.put("status", "partial");
          result.withArray("diagnostics").add("Dependency metadata incomplete: " + e.getMessage());
        }
      }
      if (!result.path("status").asText().equals("complete")) report.put("status", "partial");
      report.withArray("projects").add(result);
    }
    return report;
  }
}
