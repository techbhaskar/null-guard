package com.nullguard.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/** Queries current installed-version matches, follows pagination, and loads full OSV records. */
public final class OsvClient {
    public record Package(String ecosystem, String name, String version) {}
    public record Match(Package dependency, JsonNode advisory) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI batchEndpoint;
    private final URI detailsEndpoint;
    private final HttpClient http;

    public OsvClient() { this(URI.create("https://api.osv.dev/v1/")); }

    /** Endpoint injection permits deterministic tests against a local HTTP server. */
    public OsvClient(URI base) {
        this.batchEndpoint = base.resolve("querybatch");
        this.detailsEndpoint = base.resolve("vulns/");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public List<Match> query(List<Package> packages) throws IOException, InterruptedException {
        Map<String, JsonNode> details = new LinkedHashMap<>();
        Map<String, Match> matches = new LinkedHashMap<>();
        for (int start = 0; start < packages.size(); start += 1000) {
            List<Package> chunk = packages.subList(start, Math.min(start + 1000, packages.size()));
            Map<Package, String> pending = new LinkedHashMap<>();
            for (Package p : chunk) pending.put(p, "");
            Set<String> seenTokens = new HashSet<>();
            int pages = 0;
            while (!pending.isEmpty()) {
                if (++pages > 100) throw new IOException("OSV pagination budget exhausted; lookup incomplete");
                ObjectNode payload = JSON.createObjectNode();
                ArrayNode queries = payload.putArray("queries");
                List<Package> order = new ArrayList<>(pending.keySet());
                for (Package p : order) {
                    ObjectNode q = queries.addObject().put("version", p.version());
                    q.putObject("package").put("ecosystem", p.ecosystem()).put("name", p.name());
                    if (!pending.get(p).isEmpty()) q.put("page_token", pending.get(p));
                }
                JsonNode results = request(HttpRequest.newBuilder(batchEndpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))).path("results");
                if (!results.isArray() || results.size() != order.size())
                    throw new IOException("OSV batch response does not match submitted dependency count");
                Map<Package, String> next = new LinkedHashMap<>();
                for (int i = 0; i < order.size(); i++) {
                    Package p = order.get(i);
                    JsonNode result = results.get(i);
                    if (!result.isObject() || (result.has("vulns") && !result.path("vulns").isArray()))
                        throw new IOException("Malformed OSV batch result");
                    for (JsonNode item : result.path("vulns")) {
                        String id = item.path("id").asText();
                        if (!id.matches("[a-zA-Z0-9:_.-]+")) throw new IOException("Invalid OSV advisory identifier");
                        JsonNode record = details.get(id);
                        if (record == null) {
                            if (details.size() >= 10000) throw new IOException("OSV advisory budget exhausted; lookup incomplete");
                            record = request(HttpRequest.newBuilder(URI.create(detailsEndpoint.toString() + id)).GET());
                            if (!record.path("id").asText().equals(id) || !record.path("affected").isArray())
                                throw new IOException("Malformed OSV advisory record for " + id);
                            details.put(id, record);
                        }
                        if (!record.hasNonNull("withdrawn"))
                            matches.put(p + "@" + id, new Match(p, record));
                    }
                    String token = result.path("next_page_token").asText();
                    if (!token.isEmpty()) {
                        if (!seenTokens.add(p + "@" + token)) throw new IOException("Repeated OSV pagination token");
                        next.put(p, token);
                    }
                }
                pending = next;
            }
        }
        return List.copyOf(matches.values());
    }

    private JsonNode request(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(45)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("OSV HTTP " + response.statusCode());
        JsonNode body = JSON.readTree(response.body());
        if (body == null || !body.isObject()) throw new IOException("OSV returned an invalid JSON object");
        return body;
    }

    /** Preserve supplied facts; never replace missing severity, CVSS, or fixes with guesses. */
    public static ObjectNode metadata(Match match) {
        JsonNode record = match.advisory();
        ObjectNode out = JSON.createObjectNode();
        out.put("source", "OSV");
        out.put("advisoryUrl", "https://osv.dev/vulnerability/" + record.path("id").asText());
        out.put("published", record.path("published").asText());
        out.put("modified", record.path("modified").asText());
        out.put("summary", record.path("summary").asText("OSV advisory " + record.path("id").asText()));
        out.set("aliases", record.path("aliases").isArray() ? record.path("aliases") : JSON.createArrayNode());
        out.set("references", record.path("references").isArray() ? record.path("references") : JSON.createArrayNode());
        Set<String> fixes = new LinkedHashSet<>();
        ArrayNode vectors = out.putArray("cvssVectors");
        for (JsonNode severity : record.path("severity"))
            if (severity.hasNonNull("score")) vectors.add(severity);
        String label = record.path("database_specific").path("severity").asText("");
        for (JsonNode affected : record.path("affected")) {
            if (!affected.path("package").path("name").asText().equals(match.dependency().name())
                    || !affected.path("package").path("ecosystem").asText().equals(match.dependency().ecosystem())) continue;
            if (label.isEmpty()) label = affected.path("ecosystem_specific").path("severity").asText("");
            for (JsonNode severity : affected.path("severity"))
                if (severity.hasNonNull("score")) vectors.add(severity);
            for (JsonNode range : affected.path("ranges")) {
                if (range.path("type").asText().equals("GIT")) continue;
                for (JsonNode event : range.path("events"))
                    if (event.hasNonNull("fixed")) fixes.add(event.path("fixed").asText());
            }
        }
        label = label.toUpperCase(Locale.ROOT);
        if (!Set.of("CRITICAL", "HIGH", "MEDIUM", "MODERATE", "LOW").contains(label)) label = "UNKNOWN";
        out.put("severity", label);
        out.putNull("cvssScore"); // Vector strings are preserved; no fabricated numeric CVSS score.
        out.set("fixedVersions", JSON.valueToTree(fixes));
        return out;
    }
}
