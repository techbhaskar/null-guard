package com.nullguard.bootstrap;

import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.visualization.model.PropagationGraph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PipelineContext – shared carrier for all intermediate artifacts produced
 * during a single pipeline execution pass.
 *
 * Populated incrementally by {@link AnalysisPipeline}. Fields are intentionally
 * package-private to restrict mutation to the orchestration layer only.
 * All accessor methods return unmodifiable views.
 */
public final class PipelineContext {

    private final NullGuardConfig config;
    private final TimingMetrics   timing;

    // Intermediates set by pipeline steps (package-private setters)
    private ProjectModel                        projectModel;
    private GlobalCallGraph                     callGraph;
    private Map<String, AdjustedRiskModel>      adjustedRiskMap;
    private ProjectRiskSummary                  riskSummary;
    private List<ApiEndpointModel>              apiEndpoints;
    private List<ArchitecturalHotspot>          hotspots;
    private List<Suggestion>                    suggestions;
    private PropagationGraph                    propagationGraph;
    private VisualizationBundle                 visualizationBundle;

    PipelineContext(NullGuardConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.timing = new TimingMetrics();
    }

    // ── Package-private mutators (only AnalysisPipeline writes here) ────────

    void setProjectModel(ProjectModel projectModel) {
        this.projectModel = Objects.requireNonNull(projectModel);
    }

    void setCallGraph(GlobalCallGraph callGraph) {
        this.callGraph = Objects.requireNonNull(callGraph);
    }

    void setAdjustedRiskMap(Map<String, AdjustedRiskModel> adjustedRiskMap) {
        this.adjustedRiskMap = new LinkedHashMap<>(Objects.requireNonNull(adjustedRiskMap));
    }

    void setRiskSummary(ProjectRiskSummary riskSummary) {
        this.riskSummary = Objects.requireNonNull(riskSummary);
    }

    void setApiEndpoints(List<ApiEndpointModel> apiEndpoints) {
        this.apiEndpoints = List.copyOf(Objects.requireNonNull(apiEndpoints));
    }

    void setHotspots(List<ArchitecturalHotspot> hotspots) {
        this.hotspots = List.copyOf(Objects.requireNonNull(hotspots));
    }

    void setSuggestions(List<Suggestion> suggestions) {
        this.suggestions = List.copyOf(Objects.requireNonNull(suggestions));
    }

    void setPropagationGraph(PropagationGraph propagationGraph) {
        this.propagationGraph = Objects.requireNonNull(propagationGraph);
    }

    void setVisualizationBundle(VisualizationBundle visualizationBundle) {
        this.visualizationBundle = Objects.requireNonNull(visualizationBundle);
    }

    // ── Public read accessors ────────────────────────────────────────────────

    public NullGuardConfig getConfig() { return config; }
    public TimingMetrics   getTiming() { return timing; }

    public ProjectModel getProjectModel() {
        requireSet(projectModel, "projectModel");
        return projectModel;
    }

    public GlobalCallGraph getCallGraph() {
        requireSet(callGraph, "callGraph");
        return callGraph;
    }

    public Map<String, AdjustedRiskModel> getAdjustedRiskMap() {
        requireSet(adjustedRiskMap, "adjustedRiskMap");
        return Collections.unmodifiableMap(adjustedRiskMap);
    }

    public ProjectRiskSummary getRiskSummary() {
        requireSet(riskSummary, "riskSummary");
        return riskSummary;
    }

    public List<ApiEndpointModel> getApiEndpoints() {
        requireSet(apiEndpoints, "apiEndpoints");
        return apiEndpoints;
    }

    public List<ArchitecturalHotspot> getHotspots() {
        requireSet(hotspots, "hotspots");
        return hotspots;
    }

    public List<Suggestion> getSuggestions() {
        requireSet(suggestions, "suggestions");
        return suggestions;
    }

    public PropagationGraph getPropagationGraph() {
        requireSet(propagationGraph, "propagationGraph");
        return propagationGraph;
    }

    public VisualizationBundle getVisualizationBundle() {
        requireSet(visualizationBundle, "visualizationBundle");
        return visualizationBundle;
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static void requireSet(Object value, String name) {
        if (value == null) {
            throw new IllegalStateException(
                    "PipelineContext: '" + name + "' has not been populated yet. "
                    + "Ensure pipeline steps execute in the correct order.");
        }
    }
}
