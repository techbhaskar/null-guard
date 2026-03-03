package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;

public class ApiRiskAggregator {
    private final AnalysisConfig config;

    public ApiRiskAggregator(AnalysisConfig config) {
        this.config = config;
    }

    /**
     * Implement hybrid formula for API_RISK.
     */
    public double computeApiRisk(double maxMethodRisk, double maxPathWeightedAggregate, double nullExposurePenalty, int apiReachCount) {
        double sharedMultiplier = 1.0 + Math.log1p(apiReachCount);
        if (sharedMultiplier > 4.0) sharedMultiplier = 4.0;
        double blastRadiusFactor = sharedMultiplier;
        
        return (maxMethodRisk * 0.40)
             + (maxPathWeightedAggregate * 0.30)
             + (nullExposurePenalty * 0.20)
             + (blastRadiusFactor * 0.10);
    }
}
