package com.nullguard.analysis.orchestrator;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.summary.MethodSummaryEngine;
import com.nullguard.analysis.contract.ContractAnalyzer;
import com.nullguard.analysis.api.ApiEndpointAnalyzer;
import com.nullguard.analysis.hotspot.HotspotDetector;
import com.nullguard.analysis.config.AnalysisConfig;

import java.util.Map;
import java.util.Set;

public class AnalysisOrchestrator {
    
    private final MethodSummaryEngine methodSummaryEngine;
    private final ContractAnalyzer contractAnalyzer;
    private final ApiEndpointAnalyzer apiEndpointAnalyzer;
    private final HotspotDetector hotspotDetector;

    public AnalysisOrchestrator(AnalysisConfig config) {
        this.methodSummaryEngine = new MethodSummaryEngine(config);
        this.contractAnalyzer = new ContractAnalyzer(config);
        this.apiEndpointAnalyzer = new ApiEndpointAnalyzer(config);
        this.hotspotDetector = new HotspotDetector(config);
    }

    /**
     * Executes the deterministic single-pass integrated analysis pipeline.
     *
     * @param project   the fully-parsed project model
     * @param callEdges outgoing call edges from the pre-built GlobalCallGraph
     *                  ({@code GlobalCallGraph.getOutgoing()}) used for
     *                  controller → service → repository → external traversal
     */
    public void analyze(ProjectModel project, Map<String, Set<String>> callEdges) {
        methodSummaryEngine.run(project);
        // riskEngine.propagate(project) was here. It was an empty method body, and risk
        // propagation is actually performed by FixpointRiskPropagationEngine in
        // nullguard-scoring — the call only made the pipeline look like it did more than it did.
        contractAnalyzer.analyze(project, callEdges);

        apiEndpointAnalyzer.build(project, callEdges);

        // Push the per-method reach counts onto the model. Without this, MethodModel.setReachData
        // had no callers at all: HotspotDetector's reach count was always 0, so
        // isHotspotCandidate always failed and getArchitecturalHotspots() always returned empty.
        apiEndpointAnalyzer.getReachTracker().applyTo(project);

    }

    public HotspotDetector getHotspotDetector() {
        return hotspotDetector;
    }

    public ApiEndpointAnalyzer getApiEndpointAnalyzer() {
        return apiEndpointAnalyzer;
    }
}
