package com.nullguard.analysis.risk;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;

public class RiskEngine {
    private final AnalysisConfig config;
    private final PathRiskCalculator pathRiskCalculator;

    public RiskEngine(AnalysisConfig config) {
        this.config = config;
        this.pathRiskCalculator = new PathRiskCalculator(config);
    }

    public void propagate(ProjectModel project) {
        // Aggregates risk computation
    }
}
