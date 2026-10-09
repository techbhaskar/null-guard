package com.nullguard.plugin;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MojoIntegrationTest {
    @TempDir Path output;
    private NullGuardMojo mojo(boolean fail) throws Exception {
        var project = new MavenProject();
        project.setName("sample-orders-service");
        var build = new Build();
        var sources = Path.of("../examples/orders-service/src/main/java").toAbsolutePath().normalize();
        build.setSourceDirectory(sources.toString());
        build.setDirectory(output.toString());
        build.setOutputDirectory(output.resolve("classes").toString());
        project.setBuild(build);
        project.addCompileSourceRoot(sources.toString());
        var mojo = new NullGuardMojo();
        set(mojo, "project", project); set(mojo, "failBuild", fail); set(mojo, "failThreshold", "HIGH");
        set(mojo, "scoringDecayFactor", 0.85); set(mojo, "externalPenaltyMultiplier", 1.2);
        set(mojo, "convergenceThreshold", 0.001); set(mojo, "maxScoringIterations", 100); set(mojo, "highRiskThreshold", 60);
        return mojo;
    }
    private static void set(NullGuardMojo mojo, String name, Object value) throws Exception {
        var field = NullGuardMojo.class.getDeclaredField(name); field.setAccessible(true); field.set(mojo, value);
    }
    @Test void actualMojoFailsRiskyServiceAfterWritingAllArtifacts() throws Exception {
        assertThrows(MojoFailureException.class, () -> mojo(true).execute());
        assertTrue(Files.exists(output.resolve("nullguard/nullguard-dashboard-latest.html")));
        assertTrue(Files.exists(output.resolve("nullguard/nullguard-report-latest.json")));
        assertTrue(Files.exists(output.resolve("nullguard/nullguard-results.sarif")));
    }
    @Test void actualMojoSupportsReportOnlyMode() throws Exception { assertDoesNotThrow(() -> mojo(false).execute()); }
}
