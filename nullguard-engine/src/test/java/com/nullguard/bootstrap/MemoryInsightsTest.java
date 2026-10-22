package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryInsightsTest {
    @Test void reportsSourceSignalsWithoutClaimingRuntimeLeaks(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("MemoryExample.java"), """
            import java.util.*;
            class MemoryExample {
                static final Map<String,Object> cache = new HashMap<>();
                final ThreadLocal<Object> context = new ThreadLocal<>();
                void work() {
                    String ignored = "new Object()";
                    // new Object() in a comment is not an allocation
                    for (int i = 0; i < 3; i++) { cache.put("x", new Object()); }
                    int[] values = new int[4];
                }
            }
            """);
        var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(dir);
        var report = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph());
        var memory = report.path("memoryInsights");
        assertFalse(memory.path("runtimeObserved").asBoolean(true));
        assertEquals(1, memory.path("loopAllocationSites").asInt());
        assertEquals(1, memory.path("arrayAllocationSites").asInt());
        String sites = memory.path("reviewSites").toString();
        assertTrue(sites.contains("Static collection retention"));
        assertTrue(sites.contains("Thread-local retention"));
        assertTrue(sites.contains("MemoryExample.java"));
        String html = HtmlDashboardRenderer.render(result, "test", "", "memory example");
        assertTrue(html.contains("id=\"memory\""));
        assertTrue(html.contains("Confirmed leaks: unmeasured"));
        assertTrue(html.contains("Source allocation site counts"));
    }
}
