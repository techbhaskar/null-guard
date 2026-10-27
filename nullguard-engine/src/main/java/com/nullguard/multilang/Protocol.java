package com.nullguard.multilang;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;

public final class Protocol {
  public static final String VERSION = "1.0", ANALYZER_VERSION = "2.0.0-preview.1";
  public static final ObjectMapper JSON = new ObjectMapper();

  private Protocol() {}

  public static ObjectNode request(ProjectSpec p) {
    ObjectNode n = JSON.createObjectNode();
    n.put("protocolVersion", VERSION);
    n.put("project", p.id());
    n.put("root", p.root().toString());
    n.set("languages", JSON.valueToTree(p.languages()));
    n.set("files", JSON.valueToTree(p.files()));
    n.put("maxIterations", 64);
    return n;
  }

  public static ObjectNode empty(ProjectSpec p, String analyzer) {
    ObjectNode n = request(p);
    n.remove(List.of("root", "files", "maxIterations"));
    n.put("analyzerVersion", ANALYZER_VERSION);
    n.put("analyzer", analyzer);
    n.put("status", "complete");
    n.putArray("findings");
    n.putArray("dependencies");
    n.putArray("diagnostics");
    n.putObject("coverage");
    ObjectNode c = n.putObject("capabilities");
    c.put("safetyAnalysis", true);
    c.put("callGraph", false);
    c.put("stabilityScore", false);
    c.put("openApi", false);
    c.put("vulnerabilityReachability", false);
    return n;
  }

  public static ObjectNode failed(ProjectSpec p, String message) {
    ObjectNode n = empty(p, "unavailable");
    n.put("status", "failed");
    n.withArray("diagnostics").add(message);
    for (String l : p.languages()) {
      ObjectNode c = n.withObject("/coverage").putObject(l);
      long count =
          p.files().stream()
              .filter(f -> Language.forFile(f).map(x -> x.id.equals(l)).orElse(false))
              .count();
      c.put("discoveredFiles", count);
      c.put("analyzedFiles", 0);
      c.put("failedFiles", count);
      c.put("unsupportedFiles", 0);
      c.put("unresolvedSymbols", 0);
      c.put("budgetExhausted", false);
      c.putArray("missingDependencies");
    }
    return n;
  }

  public static ObjectNode validate(JsonNode input, ProjectSpec p) {
    if (!input.isObject()
        || !VERSION.equals(input.path("protocolVersion").asText())
        || !p.id().equals(input.path("project").asText())
        || !ANALYZER_VERSION.equals(input.path("analyzerVersion").asText()))
      throw new IllegalArgumentException("Pack protocol/project mismatch");
    if (!Set.of("complete", "partial", "failed").contains(input.path("status").asText())
        || !input.path("findings").isArray()
        || !input.path("diagnostics").isArray()
        || !input.path("coverage").isObject()
        || !input.path("analyzerVersion").isTextual()
        || !input.path("dependencies").isArray()
        || !input.path("capabilities").isObject()
        || input.path("analyzer").asText().isBlank())
      throw new IllegalArgumentException("Malformed pack response");
    if (!input.path("languages").isArray()
        || !JSON.valueToTree(p.languages()).equals(input.path("languages")))
      throw new IllegalArgumentException("Pack language mismatch");
    for (String language : p.languages()) {
      JsonNode c = input.path("coverage").path(language);
      for (String k :
          List.of(
              "discoveredFiles",
              "analyzedFiles",
              "failedFiles",
              "unsupportedFiles",
              "unresolvedSymbols"))
        if (!c.path(k).isIntegralNumber() || c.path(k).asLong() < 0)
          throw new IllegalArgumentException("Invalid coverage: " + language + "/" + k);
      if (!c.path("budgetExhausted").isBoolean() || !c.path("missingDependencies").isArray())
        throw new IllegalArgumentException("Missing coverage diagnostics: " + language);
      long expected =
          p.files().stream()
              .filter(f -> Language.forFile(f).map(l -> l.id.equals(language)).orElse(false))
              .count();
      if (c.path("discoveredFiles").asLong() != expected
          || c.path("analyzedFiles").asLong()
                  + c.path("failedFiles").asLong()
                  + c.path("unsupportedFiles").asLong()
              != expected)
        throw new IllegalArgumentException("Pack omitted source files from coverage");
      if (c.path("failedFiles").asLong() > 0
          || c.path("unsupportedFiles").asLong() > 0
          || c.path("budgetExhausted").asBoolean()
          || c.path("missingDependencies").size() > 0)
        ((ObjectNode) input).put("status", "partial");
    }
    Set<String> files = new HashSet<>(p.files());
    for (JsonNode f : input.path("findings")) {
      if (!p.languages().contains(f.path("language").asText())
          || !f.path("ruleId").asText().matches("NG-[A-Z]+-[0-9]{3}")
          || !f.path("message").isTextual()
          || !Set.of("error", "warning", "note").contains(f.path("severity").asText())
          || !Set.of("experimental", "medium", "high").contains(f.path("confidence").asText())
          || !f.path("evidence").isArray()) throw new IllegalArgumentException("Malformed finding");
      for (JsonNode related : f.path("relatedLocations"))
        if (!files.contains(related.path("path").asText())
            || related.path("startLine").asInt() < 1
            || related.path("startColumn").asInt() < 1)
          throw new IllegalArgumentException("Related location outside source files");
      JsonNode l = f.path("location");
      if (!files.contains(l.path("path").asText())
          || l.path("startLine").asInt() < 1
          || l.path("startColumn").asInt() < 1)
        throw new IllegalArgumentException("Finding outside analyzed source files");
    }
    return (ObjectNode) input;
  }
}
