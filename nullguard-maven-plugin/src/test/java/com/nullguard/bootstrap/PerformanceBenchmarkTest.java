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
        int[] sizes = Arrays.stream(System.getProperty("nullguard.benchmark.sizes", "10,100,500").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).toArray();
        int samples = Integer.getInteger("nullguard.benchmark.samples", 3);
        int warmups = Integer.getInteger("nullguard.benchmark.warmups", 1);
        if (sizes.length == 0 || Arrays.stream(sizes).anyMatch(size -> size < 1 || size > 50_000)
                || samples < 1 || samples > 20 || warmups < 0 || warmups > 10)
            throw new IllegalArgumentException("Sizes must be 1..50000, samples 1..20, warmups 0..10");
        var measurements = new ArrayList<Map<String, Object>>();
        writeReport(measurements, sizes, samples, warmups, "running");
        for (int methods : sizes) {
            // Separate roots avoid mixing sizes; modest classes avoid one artificial giant class.
            Path root = Files.createTempDirectory(sources, "methods-" + methods + "-");
            for (int offset = 0; offset < methods; offset += 100) {
                StringBuilder source = new StringBuilder("package bench; class Service" + offset / 100 + " {\n");
                for (int n = offset; n < Math.min(offset + 100, methods); n++)
                    source.append("String method").append(n).append("(String value) { if(value == null) return \"\"; return value; }\n");
                source.append("}\n");
                Files.writeString(root.resolve("Service" + offset / 100 + ".java"), source);
            }
            System.out.printf("[benchmark] %,d methods, %d classes: starting%n", methods, (methods + 99) / 100);
            for (int warmup = 0; warmup < warmups; warmup++) {
                runAndCheck(root, methods);
                System.out.printf("[benchmark] %,d methods: warmup %d complete%n", methods, warmup + 1);
            }
            long[] times = new long[samples];
            for (int sample = 0; sample < times.length; sample++) {
                long start = System.nanoTime();
                runAndCheck(root, methods);
                times[sample] = (System.nanoTime() - start) / 1_000_000;
                System.out.printf("[benchmark] %,d methods: sample %d = %,d ms%n", methods, sample + 1, times[sample]);
            }
            long[] sorted = times.clone();
            Arrays.sort(sorted);
            double median = sorted.length % 2 == 0 ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0 : sorted[sorted.length / 2];
            measurements.add(Map.of("methods", methods, "classes", (methods + 99) / 100,
                    "medianMilliseconds", median, "samplesMilliseconds", times,
                    "heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
            // Keep completed sizes available if a later large workload fails or is interrupted.
            writeReport(measurements, sizes, samples, warmups, "running");
        }
        writeReport(measurements, sizes, samples, warmups, "completed");
    }
    private void runAndCheck(Path root, int methods) {
        var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(root);
        assertEquals(methods, result.getRiskSummary().getTotalMethods());
    }
    private void writeReport(List<Map<String, Object>> measurements, int[] sizes, int samples, int warmups, String status) throws Exception {
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/benchmark.json").toFile(), Map.of(
                "javaVersion", System.getProperty("java.version"), "os", System.getProperty("os.name"),
                "processors", Runtime.getRuntime().availableProcessors(), "maxHeapBytes", Runtime.getRuntime().maxMemory(),
                "requestedSizes", sizes, "samplesPerSize", samples, "warmupsPerSize", warmups,
                "status", status, "measurements", measurements));
    }
}
