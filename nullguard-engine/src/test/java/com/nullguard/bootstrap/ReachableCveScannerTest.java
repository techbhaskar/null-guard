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
        var config = NullGuardConfig.defaults().classpathEntries(List.of(root.resolve("extra-4.2.jar"), root.resolve("classes"))).build();
        var gavs = ReachableCveScanner.discoverDependencies(root, config).stream().map(ReachableCveScanner.DependencyCoordinate::gav).toList();
        assertEquals(1, Collections.frequency(gavs, "demo:lib:1.0"));
        assertTrue(gavs.containsAll(List.of("demo:locked:2.0", "demo:plain:3.0", "*:extra:4.2")));
        assertFalse(gavs.stream().anyMatch(g -> g.contains("managed")));
        assertTrue(ReachableCveScanner.discoverDependencies(null, null).isEmpty());
    }

    @Test void malformedExternalReportDoesNotInventVulnerabilities() throws Exception {
        Path report = Files.writeString(root.resolve("broken.json"), "{broken");
        var result = ReachableCveScanner.scan(null, NullGuardConfig.defaults().cveInputFile(report).build(),
                ProjectModel.builder().projectName("empty").build(),
                new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()), List.of());
        assertTrue(result.findings().isEmpty());
        assertEquals(0, result.cveSummary().get("totalVulnerabilities"));
        assertEquals("A", result.cveSummary().get("securityGrade"));
    }
}
