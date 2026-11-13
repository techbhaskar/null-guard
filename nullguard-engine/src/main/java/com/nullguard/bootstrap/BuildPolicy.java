package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared CI policy for the CLI and build plugins. */
public final class BuildPolicy {
    private static final List<String> SEVERITIES = List.of("LOW", "MODERATE", "HIGH", "CRITICAL");
    private final boolean hotspots;
    private final String threshold;
    private final boolean apiCves;
    private final boolean incomplete;
    private final Set<String> baseline;

    public record Candidate(String id, String severity) {}

    public BuildPolicy(boolean hotspots, String threshold, boolean apiCves,
                       boolean incomplete, Path baselineFile) {
        this.hotspots = hotspots;
        this.threshold = threshold;
        this.apiCves = apiCves;
        this.incomplete = incomplete;
        this.baseline = baselineFile == null ? Set.of() : readBaseline(baselineFile);
    }

    private static Set<String> readBaseline(Path path) {
        try {
            JsonNode root = new ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(path.toFile());
            if (root == null || !root.path("version").isIntegralNumber()
                    || !root.path("version").canConvertToInt() || root.path("version").intValue() != 1 || !root.path("accepted").isArray()) {
                throw new IllegalArgumentException("Expected a version 1 baseline with an accepted array: " + path);
            }
            Set<String> accepted = new HashSet<>();
            for (JsonNode id : root.path("accepted")) {
                if (!id.isTextual() || !(id.asText().startsWith("NG005|") || id.asText().startsWith("NG401|")) || !accepted.add(id.asText())) {
                    throw new IllegalArgumentException("Baseline entries must be unique NG005 or NG401 candidate IDs: " + path);
                }
            }
            return Set.copyOf(accepted);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read baseline: " + path, e);
        }
    }

    public List<String> violations(FinalAnalysisResult result) {
        List<String> violations = new ArrayList<>();
        var report = result.getVisualizations();
        if (incomplete && Set.of("INCOMPLETE", "NO_SOURCE", "UNAVAILABLE", "UNKNOWN")
                .contains(report.getAnalysisStatus())) {
            violations.add("Source analysis status: " + report.getAnalysisStatus());
        }
        for (Candidate candidate : report.getGateCandidates()) {
            if (baseline.contains(candidate.id())) continue;
            if (candidate.id().startsWith("NG401|") ? apiCves
                    : hotspots && SEVERITIES.indexOf(candidate.severity()) >= SEVERITIES.indexOf(threshold)) {
                violations.add(candidate.id());
            }
        }
        return List.copyOf(violations);
    }
}
