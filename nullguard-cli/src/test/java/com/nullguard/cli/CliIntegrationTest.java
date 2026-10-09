package com.nullguard.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CliIntegrationTest {
    @TempDir Path output;
    @Test void failingPolicyReturnsOneAndStillWritesReports() {
        var cli = new NullGuardCliApplication();
        assertEquals(1, cli.run(new String[] {"../examples/orders-service/src/main/java", "--fail-build", "--fail-threshold=HIGH", "--output=" + output}));
        assertTrue(Files.exists(output.resolve("nullguard-report-latest.json")));
        assertTrue(Files.exists(output.resolve("nullguard-results.sarif")));
    }
    @Test void reportOnlyModePassesTheSameRiskyService() {
        assertEquals(0, new NullGuardCliApplication().run(new String[] {"../examples/orders-service/src/main/java", "--output=" + output}));
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
