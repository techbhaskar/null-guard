package com.nullguard.analysis.config;

public class AnalysisConfig {
    private final int propagationDepthLimit;
    private final double propagationDecayExponent;
    private final double apiRiskWeights;
    private final double hotspotRiskThreshold;
    private final int hotspotReachThreshold;

    public AnalysisConfig(int propagationDepthLimit, double propagationDecayExponent, double apiRiskWeights, double hotspotRiskThreshold, int hotspotReachThreshold) {
        this.propagationDepthLimit = propagationDepthLimit;
        this.propagationDecayExponent = propagationDecayExponent;
        this.apiRiskWeights = apiRiskWeights;
        this.hotspotRiskThreshold = hotspotRiskThreshold;
        this.hotspotReachThreshold = hotspotReachThreshold;
    }

    public int getPropagationDepthLimit() { return propagationDepthLimit; }
    public double getPropagationDecayExponent() { return propagationDecayExponent; }
    public double getApiRiskWeights() { return apiRiskWeights; }
    public double getHotspotRiskThreshold() { return hotspotRiskThreshold; }
    public int getHotspotReachThreshold() { return hotspotReachThreshold; }
}
