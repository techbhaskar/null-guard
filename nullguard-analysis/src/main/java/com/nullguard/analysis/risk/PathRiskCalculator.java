package com.nullguard.analysis.risk;

import com.nullguard.analysis.config.AnalysisConfig;

public class PathRiskCalculator {
    private final AnalysisConfig config;

    public PathRiskCalculator(AnalysisConfig config) {
        this.config = config;
    }

    /**
     * Computes the path risk aggregate.
     */
    public double computePathRisk(int longestPathDepth, double distance) {
        double weight = 1.0 / (1.0 + Math.pow(distance, config.getPropagationDecayExponent()));
        return longestPathDepth * weight;
    }
}
