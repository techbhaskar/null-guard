package com.nullguard.bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TimingMetrics – records wall-clock duration of each named pipeline step.
 * Deterministic ordering preserved via LinkedHashMap (insertion order = pipeline order).
 */
public final class TimingMetrics {

    private final Map<String, Long> stepDurationsMs = new LinkedHashMap<>();
    private long pipelineStartMs = 0L;
    private long pipelineEndMs   = 0L;

    // ── Pipeline-level timing ────────────────────────────────────────────────

    void markPipelineStart() {
        pipelineStartMs = System.currentTimeMillis();
    }

    void markPipelineEnd() {
        pipelineEndMs = System.currentTimeMillis();
    }

    void recordStep(String stepName, long durationMs) {
        stepDurationsMs.put(stepName, durationMs);
    }

    // ── Public accessors ─────────────────────────────────────────────────────

    public long getTotalPipelineDurationMs() {
        return pipelineEndMs - pipelineStartMs;
    }

    public Map<String, Long> getStepDurationsMs() {
        return new LinkedHashMap<>(stepDurationsMs);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TimingMetrics{total=")
                .append(getTotalPipelineDurationMs()).append("ms, steps=[");
        stepDurationsMs.forEach((name, ms) -> sb.append(name).append("=").append(ms).append("ms, "));
        if (!stepDurationsMs.isEmpty()) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("]}");
        return sb.toString();
    }
}
