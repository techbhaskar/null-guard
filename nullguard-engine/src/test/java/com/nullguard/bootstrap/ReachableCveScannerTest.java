package com.nullguard.bootstrap;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.callgraph.model.GlobalCallGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReachableCveScannerTest {
    @TempDir Path root;

    @Test void versionComparisonPadsComponentsAndHandlesQualifiers() {
        assertEquals(0, ReachableCveScanner.compareVersions("1.2", "1.2.0"));
        assertEquals(0, ReachableCveScanner.compareVersions("1.2.RC", "1.2.rc"));
        assertTrue(ReachableCveScanner.compareVersions("1.10", "1.2") > 0);
        assertTrue(ReachableCveScanner.compareVersions("1.2", "1.2.1") < 0);
        assertTrue(ReachableCveScanner.compareVersions("1.alpha", "1.beta") < 0);
    }

    @Test void discoversManifestAndClasspathDependenciesWithoutDuplicates() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><dependencies>
                <dependency><groupId>demo</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>
                <dependency><groupId>demo</groupId><artifactId>managed</artifactId></dependency>
                </dependencies></project>
                """);
        Files.writeString(root.resolve("build.gradle.kts"), "implementation(\"demo:lib:1.0\")");
        Files.writeString(root.resolve("gradle.lockfile"), "# comment\n\nempty=\ndemo:locked:2.0=runtimeClasspath\ninvalid\ndemo:plain:3.0\n");
        var config = NullGuardConfig.defaults().osvOnlineLookup(false).classpathEntries(List.of(root.resolve("extra-4.2.jar"), root.resolve("classes"))).build();
        var gavs = ReachableCveScanner.discoverDependencies(root, config).stream().map(ReachableCveScanner.DependencyCoordinate::gav).toList();
        assertEquals(1, Collections.frequency(gavs, "demo:lib:1.0"));
        assertTrue(gavs.containsAll(List.of("demo:locked:2.0", "demo:plain:3.0", "*:extra:4.2")));
        assertFalse(gavs.stream().anyMatch(g -> g.contains("managed")));
        assertTrue(ReachableCveScanner.discoverDependencies(null, null).isEmpty());
    }

    @Test void malformedExternalReportIsIncompleteAndCannotReceiveAnAGrade() throws Exception {
        Path report = Files.writeString(root.resolve("broken.json"), "{broken");
        var result = ReachableCveScanner.scan(null, NullGuardConfig.defaults().osvOnlineLookup(false).cveInputFile(report).build(),
                ProjectModel.builder().projectName("empty").build(),
                new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()), List.of());
        assertTrue(result.findings().isEmpty());
        assertEquals(0, result.cveSummary().get("totalVulnerabilities"));
        assertEquals("PARTIAL", result.cveSummary().get("status"));
        assertEquals("N/A", result.cveSummary().get("securityGrade"));
        assertNull(result.cveSummary().get("securityScore"));
        assertFalse(((List<?>) result.cveSummary().get("diagnostics")).isEmpty());
    }

    @Test void noDependenciesDoesNotReceiveAnAGrade() {
        var result = ReachableCveScanner.scan(null, NullGuardConfig.defaults().osvOnlineLookup(false).build(),
                ProjectModel.builder().projectName("empty").build(),
                new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()), List.of());
        assertEquals("NOT_RUN", result.cveSummary().get("status"));
        assertNull(result.cveSummary().get("securityScore"));
    }

    @Test void bundledCatalogIsExplicitlyLimitedWithoutOnlineLookup() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><dependencies><dependency><groupId>demo</groupId><artifactId>lib</artifactId><version>1.0</version></dependency></dependencies></project>");
        var result = ReachableCveScanner.scan(root, NullGuardConfig.defaults().osvOnlineLookup(false).build(),
                ProjectModel.builder().projectName("demo").build(),
                new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()), List.of());
        assertEquals("OFFLINE_LIMITED", result.cveSummary().get("status"));
        assertEquals("N/A", result.cveSummary().get("securityGrade"));
    }

    @Test void defaultOnlineLookupUsesCurrentRecordsInsteadOfTheBundledCatalog() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><dependencies><dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-core</artifactId><version>2.14.1</version></dependency></dependencies></project>");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String body = exchange.getRequestMethod().equals("POST")
                ? "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-current\"}]}]}"
                : "{\"id\":\"GHSA-current\",\"aliases\":[\"CVE-2026-12345\"],\"modified\":\"2026-11-04T00:00:00Z\",\"summary\":\"New test advisory\",\"affected\":[{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"org.apache.logging.log4j:log4j-core\"}}]}";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var outcome = ReachableCveScanner.scan(root, NullGuardConfig.defaults().build(),
                    ProjectModel.builder().projectName("demo").build(),
                    new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()), List.of(),
                    new com.nullguard.security.OsvClient(java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/")));
            assertEquals("COMPLETED", outcome.cveSummary().get("status"));
            assertEquals(1, outcome.cveSummary().get("totalVulnerabilities"));
            var record = (Map<?, ?>) ((List<?>) outcome.cveSummary().get("vulnerabilities")).getFirst();
            assertEquals("GHSA-current", record.get("cveId"));
            assertEquals(List.of("CVE-2026-12345"), record.get("aliases"));
            assertNull(record.get("cvssScore"));
            assertEquals("UNKNOWN", record.get("severity"));
        } finally { server.stop(0); }
    }

    @Test void failedOnlineLookupCannotReceiveACleanGrade() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><dependencies><dependency><groupId>demo</groupId><artifactId>lib</artifactId><version>1.0</version></dependency></dependencies></project>");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try {
            var outcome = ReachableCveScanner.scan(root, NullGuardConfig.defaults().osvOnlineLookup(true).build(),
                    ProjectModel.builder().projectName("demo").build(),
                    new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()), List.of(),
                    new com.nullguard.security.OsvClient(java.net.URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/")));
            assertEquals("PARTIAL", outcome.cveSummary().get("status"));
            assertNull(outcome.cveSummary().get("securityScore"));
            assertFalse(((List<?>) outcome.cveSummary().get("diagnostics")).isEmpty());
        } finally { server.stop(0); }
    }
}
