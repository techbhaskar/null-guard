package com.nullguard.bootstrap;

import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.suggestions.model.Suggestion;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * FinalAnalysisResult – the single top-level output of the NullGuard pipeline.
 *
 * Produced by {@link ResultAssembler} at the end of each analysis pass.
 * Serializable for downstream consumers (CLI printer, Maven reporter, REST API, etc.).
 */
public final class FinalAnalysisResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final ProjectRiskSummary        riskSummary;
    private final List<ApiEndpointModel>    apiEndpoints;
    private final List<ArchitecturalHotspot> hotspots;
    private final List<Suggestion>          suggestions;
    private final VisualizationBundle       visualizations;
    private final TimingMetrics             timing;

    FinalAnalysisResult(
            ProjectRiskSummary        riskSummary,
            List<ApiEndpointModel>    apiEndpoints,
            List<ArchitecturalHotspot> hotspots,
            List<Suggestion>          suggestions,
            VisualizationBundle       visualizations,
            TimingMetrics             timing) {

        this.riskSummary    = Objects.requireNonNull(riskSummary,    "riskSummary");
        this.apiEndpoints   = List.copyOf(Objects.requireNonNull(apiEndpoints,   "apiEndpoints"));
        this.hotspots       = List.copyOf(Objects.requireNonNull(hotspots,       "hotspots"));
        this.suggestions    = List.copyOf(Objects.requireNonNull(suggestions,    "suggestions"));
        this.visualizations = Objects.requireNonNull(visualizations, "visualizations");
        this.timing         = Objects.requireNonNull(timing,         "timing");
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public ProjectRiskSummary getRiskSummary()           { return riskSummary; }
    public List<ApiEndpointModel> getApiEndpoints()      { return apiEndpoints; }
    public List<ArchitecturalHotspot> getHotspots()      { return hotspots; }
    public List<Suggestion> getSuggestions()             { return suggestions; }
    public VisualizationBundle getVisualizations()       { return visualizations; }
    public TimingMetrics getTiming()                     { return timing; }

    // ── Convenience ─────────────────────────────────────────────────────────

    public boolean hasHotspotsOfSeverity(String severityLevel) {
        return hotspots.stream()
                .anyMatch(h -> h.getSeverity().equalsIgnoreCase(severityLevel));
    }

    public boolean hasHotspotsAtOrAboveSeverity(String severityLevel) {
        int targetOrdinal = severityOrdinal(severityLevel);
        return hotspots.stream()
                .anyMatch(h -> severityOrdinal(h.getSeverity()) >= targetOrdinal);
    }

    private static int severityOrdinal(String severity) {
        switch (severity.toUpperCase()) {
            case "CRITICAL": return 4;
            case "HIGH":     return 3;
            case "MODERATE": return 2;
            case "LOW":      return 1;
            default:         return 0;
        }
    }

    @Override
    public String toString() {
        return "FinalAnalysisResult{" +
               "grade=" + riskSummary.getGrade() +
               ", stabilityIndex=" + String.format("%.2f", riskSummary.getStabilityIndex()) +
               ", hotspots=" + hotspots.size() +
               ", suggestions=" + suggestions.size() +
               ", timing=" + timing.getTotalPipelineDurationMs() + "ms}";
    }
}
