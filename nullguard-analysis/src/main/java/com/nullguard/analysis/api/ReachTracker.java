package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.ReachData;
import com.nullguard.analysis.model.APIFlowTrace;
import java.util.Map;
import java.util.HashMap;

public class ReachTracker {
    private final AnalysisConfig config;
    private final Map<String, ReachData> reachMap;

    public ReachTracker(AnalysisConfig config) {
        this.config = config;
        this.reachMap = new HashMap<>();
    }
    
    public void track(APIFlowTrace trace) {
        // Count per distinct inter-method path
    }
}
