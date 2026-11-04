package com.nullguard.multilang;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import com.nullguard.security.OsvClient;
import java.time.Instant;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Exact installed-version advisories; never guesses vulnerability ranges or reachability. */
public final class AdvisoryMatcher {
  private final URI endpoint;

  public AdvisoryMatcher() {
    this(URI.create("https://api.osv.dev/v1/querybatch"));
  }

  AdvisoryMatcher(URI endpoint) {
    this.endpoint = endpoint;
  }

  public void enrich(ObjectNode project, Path input, boolean online) throws Exception {
    ArrayNode vulnerabilities = Protocol.JSON.createArrayNode();
    if (input != null) {
      if (!Files.isRegularFile(input))
        throw new IllegalArgumentException("CVE input file does not exist");
      JsonNode source = Protocol.JSON.readTree(input.toFile());
      for (JsonNode v : source.path("vulnerabilities")) matchOffline(project, v, vulnerabilities);
      for (JsonNode result : source.path("Results"))
        for (JsonNode v : result.path("Vulnerabilities")) {
          ObjectNode n = Protocol.JSON.createObjectNode();
          n.put("id", v.path("VulnerabilityID").asText());
          n.put("name", v.path("PkgName").asText());
          n.put("version", v.path("InstalledVersion").asText());
          n.put("summary", v.path("Title").asText());
          matchOffline(project, n, vulnerabilities);
        }
    }
    project.set("vulnerabilities", vulnerabilities);
    ObjectNode scan = project.putObject("advisoryScan");
    scan.put("status", online ? "PARTIAL" : input == null ? "NOT_REQUESTED" : "EXTERNAL_INPUT");
    scan.put("checkedAt", Instant.now().toString());
    scan.put("source", online ? "OSV" : input == null ? "NONE" : "EXTERNAL_REPORT");
    if (online && !project.path("dependencies").isEmpty()) {
      List<OsvClient.Package> queries = new ArrayList<>();
      for (JsonNode dependency : project.path("dependencies"))
        queries.add(new OsvClient.Package(dependency.path("ecosystem").asText(),
            dependency.path("name").asText(), dependency.path("version").asText()));
      for (OsvClient.Match match : new OsvClient(endpoint.resolve(".")).query(queries)) {
        ObjectNode dependency = Protocol.JSON.createObjectNode()
            .put("ecosystem", match.dependency().ecosystem()).put("name", match.dependency().name())
            .put("version", match.dependency().version());
        add(vulnerabilities, dependency, match.advisory().path("id").asText(),
            match.advisory().path("summary").asText("OSV exact installed-version match"));
        ((ObjectNode) vulnerabilities.get(vulnerabilities.size() - 1)).setAll(OsvClient.metadata(match));
      }
      scan.put("status", "COMPLETED");
      scan.put("queriedDependencies", queries.size());
    } else if (online) scan.put("status", "NOT_RUN");
    Map<String, JsonNode> unique = new TreeMap<>();
    vulnerabilities.forEach(
        v ->
            unique.put(
                v.path("id").asText()
                    + ":"
                    + v.path("ecosystem").asText()
                    + ":"
                    + v.path("name").asText()
                    + ":"
                    + v.path("version").asText(),
                v));
    project.set("vulnerabilities", Protocol.JSON.valueToTree(unique.values()));
    project.put("vulnerabilityReachability", "Not available");
  }

  private void matchOffline(ObjectNode p, JsonNode v, ArrayNode out) {
    for (JsonNode d : p.path("dependencies")) {
      String name = v.path("name").asText(v.path("dependency").asText());
      String version = v.path("version").asText();
      boolean exact =
          d.path("name").asText().equals(name)
              && d.path("version").asText().equals(version)
              && (!v.has("ecosystem")
                  || v.path("ecosystem").asText().equals(d.path("ecosystem").asText()));
      for (JsonNode affected : v.path("affected"))
        if (affected.path("package").path("ecosystem").asText().equals(d.path("ecosystem").asText())
            && affected.path("package").path("name").asText().equals(d.path("name").asText())) {
          for (JsonNode installed : affected.path("versions"))
            if (installed.asText().equals(d.path("version").asText())) exact = true;
          if (affected.path("ranges").size() > 0)
            p.withArray("diagnostics")
                .add(
                    "Offline advisory ranges are not evaluated; provide an exact-version scanner"
                        + " report for "
                        + v.path("id").asText());
        }
      if (exact)
        add(
            out,
            d,
            v.path("id").asText(),
            v.path("summary").asText("Offline exact installed-version match"));
    }
  }

  private void add(ArrayNode out, JsonNode dep, String id, String message) {
    if (id.isBlank()) return;
    ObjectNode n = out.addObject();
    n.put("id", id);
    n.setAll((ObjectNode) dep);
    n.put("message", message);
    n.put("reachability", "Not available");
    n.put("confidence", "advisory-match");
  }
}
