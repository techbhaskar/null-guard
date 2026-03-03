package com.nullguard.analysis.contract;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;

public class ContractAnalyzer {
    private final AnalysisConfig config;

    public ContractAnalyzer(AnalysisConfig config) {
        this.config = config;
    }

    public void analyze(ProjectModel project) {
        // Analyzes contract
    }
}
