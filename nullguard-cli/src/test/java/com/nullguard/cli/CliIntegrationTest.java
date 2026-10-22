package com.nullguard.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliIntegrationTest {
    @TempDir Path output;
    @Test void usageAndMissingPositionalSourceReturnBadArguments() {
        var cli = new NullGuardCliApplication();
        assertEquals(2, cli.run(new String[0]));
        assertEquals(2, cli.run(new String[] {"--fail-build"}));
    }

    @Test void customScoringAndClasspathOptionsStillProduceReports() throws Exception {
        Path source = Files.createDirectories(output.resolve("src"));
        Files.writeString(source.resolve("Safe.java"), "class Safe { public int value() { return 1; } }");
        Path reports = output.resolve("reports");
        String classpath = source + java.io.File.pathSeparator + java.io.File.pathSeparator + source;
        assertEquals(0, new NullGuardCliApplication().run(new String[] {source.toString(), "ignored-positional",
                "--decay-factor=0.5", "--ext-penalty=1.5", "--convergence=0.01", "--max-iterations=25",
                "--high-risk-threshold=75", "--classpath=" + classpath, "--output=" + reports}));
        assertTrue(Files.exists(reports.resolve("nullguard-results.sarif")));
        var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reports.resolve("nullguard-report-latest.json").toFile());
        assertTrue(report.has("analysisCompleteness"));
    }

    @Test void emptySourceProducesNoDataSummaryAndReports() throws Exception {
        Path source = Files.createDirectories(output.resolve("empty"));
        assertEquals(0, new NullGuardCliApplication().run(new String[] {source.toString(), "--output=" + output.resolve("reports")}));
        assertTrue(Files.exists(output.resolve("reports/nullguard-report-latest.json")));
    }

    @Test void reportWriteFailureReturnsAnalysisError() throws Exception {
        Path source = Files.createDirectories(output.resolve("src"));
        Files.writeString(source.resolve("Safe.java"), "class Safe { int value() { return 1; } }");
        Path blocked = Files.writeString(output.resolve("blocked"), "a file cannot be a report directory");
        assertEquals(3, new NullGuardCliApplication().run(new String[] {source.toString(), "--output=" + blocked}));
    }
    @Test void failingPolicyReturnsOneAndStillWritesReports() {
        var cli = new NullGuardCliApplication();
        assertEquals(1, cli.run(new String[] {"../examples/orders-service/src/main/java", "--fail-build", "--fail-threshold=HIGH", "--output=" + output}));
        assertTrue(Files.exists(output.resolve("nullguard-report-latest.json")));
        assertTrue(Files.exists(output.resolve("nullguard-results.sarif")));
        assertTrue(Files.exists(output.resolve("nullguard-dashboard-latest.html")));
    }
    @Test void reportOnlyModePassesTheSameRiskyService() throws Exception {
        assertEquals(0, new NullGuardCliApplication().run(new String[] {"../examples/orders-service/src/main/java", "--output=" + output}));
        var report = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.resolve("nullguard-report-latest.json").toFile());
        assertTrue(report.has("analysisCompleteness"));
        assertFalse(report.path("analysisCompleteness").path("runtimeObserved").asBoolean(true));
        assertTrue(Files.readString(output.resolve("nullguard-dashboard-latest.html")).contains("Analysis completeness"));
    }
    @Test void missingSourceAndUnknownOptionsReturnTwo() {
        assertEquals(2, new NullGuardCliApplication().run(new String[] {output.resolve("missing").toString()}));
        assertEquals(2, new NullGuardCliApplication().run(new String[] {output.toString(), "--unknown"}));
        assertEquals(2, new NullGuardCliApplication().run(new String[] {output.toString(), "--fail-threshold=TYPO"}));
        assertEquals(2, new NullGuardCliApplication().run(new String[] {output.toString(), "--max-iterations=oops"}));
    }
    @Test void safeServicePassesWithGateEnabled() {
        assertEquals(0, new NullGuardCliApplication().run(new String[] {"../examples/catalog-service/src/main/java", "--fail-build", "--fail-threshold=LOW", "--output=" + output}));
    }
}
