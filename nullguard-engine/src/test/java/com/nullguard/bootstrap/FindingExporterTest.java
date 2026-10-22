package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nullguard.core.model.*;
import com.nullguard.suggestions.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FindingExporterTest {
    @TempDir Path root;
    @Test void suggestionRulesRemainAvailableWithoutSourceLocations() throws Exception {
        var suggestions = Arrays.stream(SuggestionType.values()).map(type ->
                new Suggestion("unknown#method()", type, "Fix " + type, 10, .9, .8, 0)).toList();
        var exporter = new FindingExporter();
        var findings = exporter.collect(ProjectModel.builder().projectName("empty").build(), List.of(), suggestions, null);
        assertEquals(Set.of("NG101", "NG102", "NG103", "NG104", "NG105"),
                findings.stream().map(Finding::ruleId).collect(java.util.stream.Collectors.toSet()));
        var results = new ObjectMapper().readTree(exporter.sarif(findings, root)).path("runs").get(0).path("results");
        assertEquals(5, results.size());
        for (var result : results) assertFalse(result.has("locations"));
    }

    @Test void sarifEncodesPathsAndUsesExclusiveEndColumns() throws Exception {
        var exporter = new FindingExporter();
        var findings = List.of(new Finding("NG001", "warning", "demo#read()", "nullable receiver",
                new SourceLocation("src/with space/Ünicode.java", 2, 3, 4, 7)));
        var sarif = new ObjectMapper().readTree(exporter.sarif(findings, root));
        var physical = sarif.path("runs").get(0).path("results").get(0).path("locations").get(0).path("physicalLocation");
        assertTrue(physical.path("artifactLocation").path("uri").asText().contains("with%20space"));
        assertEquals(8, physical.path("region").path("endColumn").asInt());
        assertEquals(4, physical.path("region").path("endLine").asInt());
    }
}
