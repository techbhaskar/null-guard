package com.nullguard.bootstrap;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceSelectionConfigTest {
    @Test void projectFileSelectionPreservesRuntimeScoringAndOutputConfiguration() {
        var original = NullGuardConfig.defaults().osvOnlineLookup(false).failBuild(true).failThreshold("HIGH")
            .scoringDecayFactor(.6).highRiskThreshold(75).outputDirectory(Path.of("reports"))
            .sourceRoots(List.of(Path.of("src"))).classpathEntries(List.of(Path.of("dependency.jar")))
            .sastClassDirectories(List.of(Path.of("classes"))).jacocoReport(Path.of("jacoco.xml"))
            .cveInputFile(Path.of("advisories.json")).build();
        var files = new ArrayList<>(List.of(Path.of("src/Example.java")));
        var selected = original.withSelectedSourceFiles(files);
        files.clear();
        assertEquals(List.of(Path.of("src/Example.java")), selected.getSelectedSourceFiles());
        assertTrue(original.getSelectedSourceFiles().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> selected.getSelectedSourceFiles().clear());
        assertFalse(selected.isOsvOnlineLookup());
        assertTrue(selected.isFailBuild());
        assertEquals("HIGH", selected.getFailThreshold());
        assertEquals(original.getOutputDirectory(), selected.getOutputDirectory());
        assertEquals(original.getSourceRoots(), selected.getSourceRoots());
        assertEquals(original.getClasspathEntries(), selected.getClasspathEntries());
        assertEquals(original.getSastClassDirectories(), selected.getSastClassDirectories());
        assertEquals(original.getJacocoReport(), selected.getJacocoReport());
        assertEquals(original.getCveInputFile(), selected.getCveInputFile());
        assertSame(original.getOpenApiOptions(), selected.getOpenApiOptions());
        assertEquals(original.toScoringConfig().getDecayFactor(), selected.toScoringConfig().getDecayFactor());
    }
}
