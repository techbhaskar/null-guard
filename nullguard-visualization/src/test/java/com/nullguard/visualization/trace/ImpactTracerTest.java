package com.nullguard.visualization.trace;

import com.nullguard.callgraph.model.GlobalCallGraph;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImpactTracerTest {

    @Test
    void testTraceImpactPathDepthLimiting() {
        LinkedHashMap<String, LinkedHashSet<String>> incoming = new LinkedHashMap<>();
        // C is called by B, B is called by A
        incoming.put("C", new LinkedHashSet<>(Collections.singletonList("B")));
        incoming.put("B", new LinkedHashSet<>(Collections.singletonList("A")));
        incoming.put("A", new LinkedHashSet<>());

        GlobalCallGraph callGraph = new GlobalCallGraph(new LinkedHashMap<>(), incoming, new LinkedHashSet<>());

        ImpactTracer tracer = new ImpactTracer();
        List<String> pathDepth1 = tracer.traceImpactPath("C", callGraph, 1);
        assertEquals(Arrays.asList("C", "B"), pathDepth1);

        List<String> pathDepth2 = tracer.traceImpactPath("C", callGraph, 2);
        assertEquals(Arrays.asList("C", "B", "A"), pathDepth2);
    }
}
