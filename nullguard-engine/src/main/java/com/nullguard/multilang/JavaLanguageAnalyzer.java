package com.nullguard.multilang;

import com.fasterxml.jackson.databind.node.*;
import com.nullguard.bootstrap.*;
import java.nio.file.*;

/** Adapts existing Java behavior without changing schema 1 or Maven/Gradle gates. */
public final class JavaLanguageAnalyzer implements LanguageAnalyzer {
  private final NullGuardConfig config;

  public JavaLanguageAnalyzer(NullGuardConfig config) {
    this.config = config;
  }

  @Override
  public ObjectNode analyze(ProjectSpec project) throws Exception {
    // Stage exactly the files owned by this project so nested modules are not counted twice.
    Path staged = Files.createTempDirectory("nullguard-java-source-");
    try {
      for (String file : project.files()) {
        Path target = staged.resolve(file);
        Files.createDirectories(target.getParent());
        Files.copy(project.root().resolve(file), target);
      }
      var result = new EngineBootstrap(config).run(staged);
      ObjectNode out = Protocol.empty(project, "java");
      var report = Protocol.JSON.readTree(result.getVisualizations().getJsonGraph());
      out.set("legacySummary", report.path("summary"));
      ObjectNode caps = (ObjectNode) out.get("capabilities");
      caps.put("callGraph", true);
      caps.put("stabilityScore", true);
      caps.put("openApi", true);
      for (var f : result.getFindings()) {
        ObjectNode n = Protocol.JSON.valueToTree(f);
        n.put("language", "java");
        n.put("confidence", "experimental");
        n.putArray("evidence").add("Existing Java analyzer finding");
        n.putArray("relatedLocations");
        out.withArray("findings").add(n);
      }
      ObjectNode coverage = out.withObject("/coverage").putObject("java");
      var parse = report.path("analysisCompleteness");
      out.set("legacyCompleteness", parse);
      long discovered = project.files().size();
      long problems = Math.min(discovered, parse.path("sourceFilesWithProblems").asLong());
      coverage.put("discoveredFiles", discovered);
      coverage.put("analyzedFiles", Math.max(0, discovered - problems));
      coverage.put("failedFiles", problems);
      coverage.put("unsupportedFiles", 0);
      coverage.put("unresolvedSymbols", parse.path("unresolvedCallSites").size());
      coverage.put("budgetExhausted", false);
      coverage.putArray("missingDependencies");
      if (problems > 0 || parse.path("status").asText().equals("INCOMPLETE")) {
        out.put("status", "partial");
        out.withArray("diagnostics").add("Java parse failures; inspect parser diagnostics");
      }
      return out;
    } finally {
      PackManager.deleteTree(staged);
    }
  }
}
