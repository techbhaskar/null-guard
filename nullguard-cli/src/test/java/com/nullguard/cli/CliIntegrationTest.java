package com.nullguard.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CliIntegrationTest {
    @TempDir Path output;
    @Test void sourceManifestReadsOriginalFilesAcrossRootsAndLeavesOtherSourcesOut() throws Exception {
        Path root = Files.createDirectories(output.resolve("project with spaces"));
        Path first = Files.createDirectories(root.resolve("src/main/java/left")).resolve("Main.java");
        Path second = Files.createDirectories(output.resolve("generated/right")).resolve("Main.java");
        String original = "package left; class Main { int bad() { String x=null; return x.length(); } }";
        Files.writeString(first, original);
        Files.writeString(second, "package right; class Main { int safe() { return 1; } }");
        Files.writeString(root.resolve("Excluded.java"), "class Excluded { broken syntax");
        var manifest = new java.util.Properties();
        manifest.setProperty("file.0", first.toString()); manifest.setProperty("file.1", second.toString());
        manifest.setProperty("root.0", root.resolve("src/main/java").toString());
        manifest.setProperty("root.1", output.resolve("generated").toString());
        Path file=output.resolve("sources.properties");
        try (var writer=Files.newBufferedWriter(file)) { manifest.store(writer, "Original paths only"); }
        Path reports=output.resolve("reports");
        assertEquals(0,new NullGuardCliApplication().run(new String[]{root.toString(),"--source-manifest="+file,"--cve-offline","--output="+reports}));
        var report=new com.fasterxml.jackson.databind.ObjectMapper().readTree(reports.resolve("nullguard-report-latest.json").toFile());
        assertEquals(2,report.path("analysisCompleteness").path("sourceFilesDiscovered").asInt());
        assertEquals(2,report.path("analysisCompleteness").path("sourceFilesParsed").asInt());
        String sarif=Files.readString(reports.resolve("nullguard-results.sarif"));
        var sarifRun = new com.fasterxml.jackson.databind.ObjectMapper().readTree(sarif).path("runs").get(0);
        var base = java.net.URI.create(sarifRun.path("originalUriBaseIds").path("SOURCE_ROOT").path("uri").asText());
        assertEquals(first.toUri(), base.resolve(sarifRun.path("results").get(0).path("locations").get(0).path("physicalLocation").path("artifactLocation").path("uri").asText()));
        assertFalse(sarif.contains("build/tmp/nullguard/sources"));
        assertEquals(original,Files.readString(first));
    }

    @Test void missingEmptyAndUnavailableSourceManifestsFailClearly() throws Exception {
        Path root=Files.createDirectories(output.resolve("project"));
        var cli=new NullGuardCliApplication();
        Path manifest=output.resolve("missing.properties");
        String[] args={root.toString(),"--source-manifest="+manifest,"--cve-offline"};
        assertEquals(2,cli.run(args));
        Files.writeString(manifest,"");
        assertEquals(2,cli.run(args));
        Files.writeString(manifest,"file.0=missing.java");
        assertEquals(2,cli.run(args));
    }

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

    @Test void cveLookupDefaultsOnlineAndOfflineFlagCanOverrideIt() throws Exception {
        Path source = Files.createDirectories(output.resolve("source"));
        Files.writeString(source.resolve("Safe.java"), "class Safe { int value() { return 1; } }");
        for (boolean offline : new boolean[] {false, true}) {
            Path reports = output.resolve(offline ? "offline" : "default");
            var arguments = new java.util.ArrayList<>(List.of(source.toString(), "--output=" + reports));
            if (offline) arguments.add("--cve-offline");
            assertEquals(0, new NullGuardCliApplication().run(arguments.toArray(String[]::new)));
            var report = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(reports.resolve("nullguard-report-latest.json").toFile());
            assertEquals(!offline, report.path("cveSummary").path("onlineRequested").asBoolean());
        }
    }

    @Test void cliDiscoversCompiledClassesInNestedMavenModules() throws Exception {
        Path workspace = Files.createDirectories(output.resolve("workspace"));
        Path firstModuleClasses = Files.createDirectories(workspace.resolve("module-one/target/classes"));
        Path secondModuleClasses = Files.createDirectories(workspace.resolve("nested/module-two/target/classes"));
        Files.createDirectories(workspace.resolve("module-one/src/main/java"));
        Files.createDirectories(workspace.resolve("nested/module-two/src/main/java"));
        Files.writeString(workspace.resolve("module-one/src/main/java/First.java"), "class First {}");
        Files.writeString(workspace.resolve("nested/module-two/src/main/java/Second.java"), "class Second {}");
        copyFixtureClass(SastFixtureOne.class, firstModuleClasses);
        copyFixtureClass(SastFixtureTwo.class, secondModuleClasses);
        Path reports = output.resolve("nested-sast-reports");

        assertEquals(0, new NullGuardCliApplication().run(new String[] {
                workspace.toString(), "--output=" + reports
        }));

        var report = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(reports.resolve("nullguard-report-latest.json").toFile());
        assertTrue(List.of("COMPLETED", "PARTIAL")
                .contains(report.path("sastSummary").path("status").asText()));
        assertEquals(2, report.path("sastSummary").path("scannedClassCount").asInt());
    }

    @Test void explicitSastPathsWorkThroughCliAndSystemProperty() throws Exception {
        Path source = Files.createDirectories(output.resolve("explicit-source"));
        Files.writeString(source.resolve("Safe.java"), "class Safe {}");
        Path first = Files.createDirectories(output.resolve("first compiled classes"));
        Path second = Files.createDirectories(output.resolve("second compiled classes"));
        copyFixtureClass(SastFixtureOne.class, first);
        copyFixtureClass(SastFixtureTwo.class, second);
        String paths = first + java.io.File.pathSeparator + java.io.File.pathSeparator + second;
        String previous = System.getProperty("nullguard.sast.classes");
        try {
            for (boolean systemProperty : new boolean[] {false, true}) {
                System.clearProperty("nullguard.sast.classes");
                Path reports = output.resolve(systemProperty ? "property-reports" : "option-reports");
                var arguments = new java.util.ArrayList<>(List.of(source.toString(), "--output=" + reports));
                if (systemProperty) System.setProperty("nullguard.sast.classes", paths);
                else arguments.add("--sast-classes=" + paths);
                assertEquals(0, new NullGuardCliApplication().run(arguments.toArray(String[]::new)));
                var report = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(reports.resolve("nullguard-report-latest.json").toFile());
                assertTrue(List.of("COMPLETED", "PARTIAL")
                        .contains(report.path("sastSummary").path("status").asText()));
                assertEquals(2, report.path("sastSummary").path("scannedClassCount").asInt());
            }
        } finally {
            if (previous == null) System.clearProperty("nullguard.sast.classes");
            else System.setProperty("nullguard.sast.classes", previous);
        }
    }

    private static void copyFixtureClass(Class<?> fixture, Path classesDirectory) throws Exception {
        Path classFile = classesDirectory.resolve(fixture.getName().replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        try (var input = CliIntegrationTest.class.getResourceAsStream(
                "/" + fixture.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            Files.copy(input, classFile);
        }
    }

    private static final class SastFixtureOne {}
    private static final class SastFixtureTwo {}

    @Test void reportWriteFailureReturnsAnalysisError() throws Exception {
        Path source = Files.createDirectories(output.resolve("src"));
        Files.writeString(source.resolve("Safe.java"), "class Safe { int value() { return 1; } }");
        Path blocked = Files.writeString(output.resolve("blocked"), "a file cannot be a report directory");
        assertEquals(3, new NullGuardCliApplication().run(new String[] {source.toString(), "--output=" + blocked}));
    }
    @Test void failingPolicyReturnsOneAndStillWritesReports() {
        var cli = new NullGuardCliApplication();
        assertEquals(1, cli.run(new String[] {"../examples/orders-service/src/main/java", "--cve-offline", "--fail-build", "--fail-threshold=HIGH", "--output=" + output}));
        assertTrue(Files.exists(output.resolve("nullguard-report-latest.json")));
        assertTrue(Files.exists(output.resolve("nullguard-results.sarif")));
        assertTrue(Files.exists(output.resolve("nullguard-dashboard-latest.html")));
    }
    @Test void reportOnlyModePassesTheSameRiskyService() throws Exception {
        assertEquals(0, new NullGuardCliApplication().run(new String[] {"../examples/orders-service/src/main/java", "--cve-offline", "--output=" + output}));
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
        assertEquals(0, new NullGuardCliApplication().run(new String[] {"../examples/catalog-service/src/main/java", "--cve-offline", "--fail-build", "--fail-threshold=LOW", "--output=" + output}));
    }
}
