package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in end-to-end workload benchmark; timings are observations, not release guarantees. */
@EnabledIfSystemProperty(named = "nullguard.benchmark", matches = "true")
class PerformanceBenchmarkTest {
    @TempDir Path sources;
    @Test void measuresIncreasingProjectSizesAfterWarmup() throws Exception {
        var measurements = new ArrayList<Map<String, Object>>();
        for (int methods : new int[] {10, 100, 500}) {
            StringBuilder source = new StringBuilder("package bench; class Service {");
            for (int n = 0; n < methods; n++) source.append("String method").append(n).append("(String value) { if(value == null) return \"\"; return value; }");
            source.append("}");
            Files.writeString(sources.resolve("Service.java"), source);
            new EngineBootstrap(NullGuardConfig.defaults().build()).run(sources); // warmup
            long[] times = new long[3];
            for (int sample = 0; sample < times.length; sample++) {
                long start = System.nanoTime();
                var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(sources);
                times[sample] = (System.nanoTime() - start) / 1_000_000;
                assertEquals(methods, result.getRiskSummary().getTotalMethods());
            }
            Arrays.sort(times);
            measurements.add(Map.of("methods", methods, "medianMilliseconds", times[1], "samplesMilliseconds", times,
                    "heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
        }
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmark.json").toFile(), Map.of(
                "javaVersion", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "processors", Runtime.getRuntime().availableProcessors(), "measurements", measurements));
    }
}
