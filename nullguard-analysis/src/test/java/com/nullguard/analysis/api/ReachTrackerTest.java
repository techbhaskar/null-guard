package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.APIFlowTrace;
import com.nullguard.analysis.model.ApiFlowGraph;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReachTrackerTest {
    @Test void reachCountsDistinctEntriesAndSnapshotsSurviveReset() {
        var tracker = new ReachTracker(new AnalysisConfig(10, .85, 1, 60, 2));
        tracker.track(new APIFlowTrace(List.of()));
        tracker.track(new APIFlowTrace(List.of("entry")));
        tracker.track(new APIFlowTrace(List.of("entry", "shared")));
        tracker.track(new APIFlowTrace(List.of("entry")));
        tracker.track(new APIFlowTrace(List.of("other", "shared", "leaf")));
        assertEquals(List.of("shared"), tracker.getReachMap().get("entry").getReachableApis());
        var snapshot = tracker.getReachedBy();
        assertEquals(Set.of("entry", "other"), snapshot.get("shared"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get("shared").clear());
        assertThrows(UnsupportedOperationException.class, () -> tracker.getReachMap().clear());
        tracker.reset();
        assertTrue(tracker.getReachedBy().isEmpty());
        assertTrue(tracker.getReachMap().isEmpty());
        assertEquals(Set.of("entry", "other"), snapshot.get("shared"));
    }

    @Test void flowGraphIndexesSharedMethodsAndCopiesInputChains() {
        var chain = new ArrayList<>(List.of("entry", "shared"));
        var graph = ApiFlowGraph.builder().addEndpoint("GET /one", chain)
                .addEndpoint("GET /two", List.of("other", "shared")).addEndpoint("empty", null).build();
        chain.clear();
        assertEquals(List.of("entry", "shared"), graph.getChain("GET /one"));
        assertEquals(List.of("GET /one", "GET /two"), graph.getEndpointsForMethod("shared"));
        assertEquals(2, graph.getApiReachCount("shared"));
        assertEquals(0, graph.getApiReachCount("absent"));
        assertTrue(graph.getChain("absent").isEmpty());
        assertTrue(graph.getChain("empty").isEmpty());
        assertTrue(graph.getEndpointsForMethod("absent").isEmpty());
        assertEquals(Set.of("GET /one", "GET /two", "empty"), graph.getEndpointIds());
        assertThrows(UnsupportedOperationException.class, () -> graph.getChain("GET /one").clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.getEndpointIds().clear());
        assertThrows(NullPointerException.class, () -> ApiFlowGraph.builder().addEndpoint(null, List.of()));
    }
}
