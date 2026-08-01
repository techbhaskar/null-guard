package com.nullguard.analysis.orchestrator;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.summary.MethodSummaryEngine;
import com.nullguard.analysis.risk.RiskEngine;
import com.nullguard.analysis.contract.ContractAnalyzer;
import com.nullguard.analysis.api.ApiEndpointAnalyzer;
import com.nullguard.analysis.hotspot.HotspotDetector;
import com.nullguard.analysis.config.AnalysisConfig;

import java.util.Map;
import java.util.Set;

public class AnalysisOrchestrator {
    
    private final MethodSummaryEngine methodSummaryEngine;
    private final RiskEngine riskEngine;
    private final ContractAnalyzer contractAnalyzer;
    private final ApiEndpointAnalyzer apiEndpointAnalyzer;
    private final HotspotDetector hotspotDetector;

    public AnalysisOrchestrator(AnalysisConfig config) {
        this.methodSummaryEngine = new MethodSummaryEngine(config);
        this.riskEngine = new RiskEngine(config);
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
        riskEngine.propagate(project);
        contractAnalyzer.analyze(project);

        apiEndpointAnalyzer.build(project, callEdges);

        hotspotDetector.finalize(project);
    }

    public HotspotDetector getHotspotDetector() {
        return hotspotDetector;
    }

    public ApiEndpointAnalyzer getApiEndpointAnalyzer() {
        return apiEndpointAnalyzer;
    }
}
