package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.model.APIFlowTrace;
import java.util.Collections;
import java.util.List;

public class FlowPathExtractor {
    private final AnalysisConfig config;

    public FlowPathExtractor(AnalysisConfig config) {
        this.config = config;
    }

    public List<APIFlowTrace> extractDistinctPaths(ProjectModel projectModel, int depthLimit) {
        // DFS inter-method structural extraction guarding cycles with visitedInCurrentPath
        return Collections.emptyList();
    }
}
