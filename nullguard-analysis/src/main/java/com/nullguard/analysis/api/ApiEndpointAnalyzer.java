package com.nullguard.analysis.api;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.APIFlowTrace;
import java.util.Collections;
import java.util.List;

public class ApiEndpointAnalyzer {
    private final AnalysisConfig config;
    private final FlowPathExtractor flowPathExtractor;
    private final ApiRiskAggregator apiRiskAggregator;
    private final ReachTracker reachTracker;

    public ApiEndpointAnalyzer(AnalysisConfig config) {
        this.config = config;
        this.flowPathExtractor = new FlowPathExtractor(config);
        this.apiRiskAggregator = new ApiRiskAggregator(config);
        this.reachTracker = new ReachTracker(config);
    }

    /**
     * Extracts inter-method paths to compute risk and reachability.
     */
    public void build(ProjectModel project) {
        List<APIFlowTrace> traces = flowPathExtractor.extractDistinctPaths(project, config.getPropagationDepthLimit());
        for (APIFlowTrace trace : traces) {
            reachTracker.track(trace);
        }
    }
}
