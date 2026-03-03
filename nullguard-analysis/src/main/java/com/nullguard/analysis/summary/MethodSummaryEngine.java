package com.nullguard.analysis.summary;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;

public class MethodSummaryEngine {
    private final AnalysisConfig config;

    public MethodSummaryEngine(AnalysisConfig config) {
        this.config = config;
    }

    public void run(ProjectModel project) {
        // Enriches method summaries
    }
}
