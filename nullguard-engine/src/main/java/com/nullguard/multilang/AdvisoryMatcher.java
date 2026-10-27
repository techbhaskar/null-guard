package com.nullguard.multilang;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
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
    if (online && !project.path("dependencies").isEmpty()) {
      ObjectNode query = Protocol.JSON.createObjectNode();
      ArrayNode batch = query.putArray("queries");
      for (JsonNode d : project.path("dependencies")) {
        ObjectNode q = batch.addObject();
        q.set(
            "package",
            Protocol.JSON
                .createObjectNode()
                .put("ecosystem", d.path("ecosystem").asText())
                .put("name", d.path("name").asText()));
        q.put("version", d.path("version").asText());
      }
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
      var response =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(Duration.ofSeconds(45))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(query.toString()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("OSV HTTP " + response.statusCode());
      JsonNode results = Protocol.JSON.readTree(response.body()).path("results");
      if (!results.isArray() || results.size() != batch.size())
        throw new IllegalStateException("OSV batch response mismatch");
      for (int i = 0; i < results.size(); i++)
        for (JsonNode v : results.get(i).path("vulns"))
          add(
              vulnerabilities,
              project.path("dependencies").get(i),
              v.path("id").asText(),
              "OSV exact installed-version match; review the advisory for details");
    }
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
