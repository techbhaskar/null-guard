package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.APIFlowTrace;
import com.nullguard.analysis.model.ReachData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReachTracker {
    private final AnalysisConfig config;
    private final Map<String, ReachData> reachMap;

    public ReachTracker(AnalysisConfig config) {
        this.config = config;
        this.reachMap = new LinkedHashMap<>();
    }

    /**
     * Records a distinct inter-method flow trace keyed by the entry-point method ID.
     * For each trace the count of reachable callees is recorded.
     */
    public void track(APIFlowTrace trace) {
        List<String> path = trace.getPath();
        if (path.isEmpty()) return;

        String entryPoint = path.get(0);
        // All nodes after the entry point are "reachable APIs / methods"
        List<String> reachable = path.size() > 1 ? path.subList(1, path.size()) : List.of();

        ReachData existing = reachMap.get(entryPoint);
        if (existing == null) {
            reachMap.put(entryPoint, new ReachData(reachable.size(), reachable, true));
        } else {
            // Merge: keep the longer reach set
            List<String> merged = existing.getReachableApis().size() >= reachable.size()
                    ? existing.getReachableApis()
                    : reachable;
            reachMap.put(entryPoint, new ReachData(merged.size(), merged, true));
        }
    }

    public Map<String, ReachData> getReachMap() {
        return reachMap;
    }
}
