package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimingMetricsTest {
    @Test
    void recordsPipelineAndStepDurationsWithoutExposingMutableState() {
        TimingMetrics metrics = new TimingMetrics();
        metrics.markPipelineStart();
        metrics.recordStep("parse", 12);
        metrics.markPipelineEnd();

        assertTrue(metrics.getTotalPipelineDurationMs() >= 0);
        assertEquals(12, metrics.getStepDurationsMs().get("parse"));
        metrics.getStepDurationsMs().put("mutated", 99L);
        assertFalse(metrics.getStepDurationsMs().containsKey("mutated"));
        assertEquals("TimingMetrics{total=" + metrics.getTotalPipelineDurationMs()
                + "ms, steps=[parse=12ms]}", metrics.toString());
    }

    @Test
    void rendersEmptyStepList() {
        assertEquals("TimingMetrics{total=0ms, steps=[]}", new TimingMetrics().toString());
    }
}
