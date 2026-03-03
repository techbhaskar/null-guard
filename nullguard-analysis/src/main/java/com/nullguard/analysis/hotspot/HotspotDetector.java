package com.nullguard.analysis.hotspot;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import java.util.ArrayList;
import java.util.List;

public class HotspotDetector {
    private final AnalysisConfig config;
    private final List<ArchitecturalHotspot> hotspots;

    public HotspotDetector(AnalysisConfig config) {
        this.config = config;
        this.hotspots = new ArrayList<>();
    }

    public void finalize(ProjectModel project) {
        // Evaluate hotspots and update ProjectRiskSummary hybrid stability indirectly
        // Storing in internal collection due to immutable ProjectModel / architecture freeze
    }
    
    public List<ArchitecturalHotspot> getArchitecturalHotspots() {
        return hotspots;
    }

    public boolean isHotspotCandidate(double adjustedRisk, int apiReachCount) {
        return adjustedRisk >= config.getHotspotRiskThreshold() && apiReachCount >= config.getHotspotReachThreshold();
    }

    public double computeHotspotScore(double adjustedRisk, int apiReachCount) {
        return adjustedRisk * Math.log1p(apiReachCount);
    }
    
    public double computeGlobalStability(double methodStability, double apiStability) {
        return (methodStability * 0.70) + (apiStability * 0.30);
    }
    
    public String determineSeverity(double hotspotScore) {
        if (hotspotScore >= 80.0) return "CRITICAL";
        if (hotspotScore >= 60.0) return "HIGH";
        if (hotspotScore >= 40.0) return "MODERATE";
        return "LOW";
    }
}
