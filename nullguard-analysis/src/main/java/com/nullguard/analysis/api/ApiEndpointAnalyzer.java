package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.APIFlowTrace;
import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.model.ReachData;
import com.nullguard.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ApiEndpointAnalyzer {
    private final AnalysisConfig config;
    private final FlowPathExtractor flowPathExtractor;
    private final ApiRiskAggregator apiRiskAggregator;
    private final ReachTracker reachTracker;

    // Assembled after build() is called
    private List<ApiEndpointModel> endpoints = Collections.emptyList();

    public ApiEndpointAnalyzer(AnalysisConfig config) {
        this.config = config;
        this.flowPathExtractor = new FlowPathExtractor(config);
        this.apiRiskAggregator = new ApiRiskAggregator(config);
        this.reachTracker = new ReachTracker(config);
    }

    /**
     * Extracts API entry points and their full downstream propagation chains.
     */
    public void build(ProjectModel project) {
        List<APIFlowTrace> traces = flowPathExtractor.extractDistinctPaths(
                project, config.getPropagationDepthLimit());

        List<ApiEndpointModel> built = new ArrayList<>();
        for (APIFlowTrace trace : traces) {
            reachTracker.track(trace);

            if (trace.getPath().isEmpty()) continue;

            String entryPoint = trace.getPath().get(0);
            String httpMethod = FlowPathExtractor.inferHttpMethod(entryPoint);
            String apiPath    = inferPath(entryPoint);

            built.add(new ApiEndpointModel(
                    entryPoint,
                    httpMethod,
                    apiPath,
                    trace.getPath()   // full chain: entry → ... → leaf
            ));
        }
        this.endpoints = Collections.unmodifiableList(built);
    }

    /** Returns the assembled API endpoint list (empty until build() is called). */
    public List<ApiEndpointModel> getEndpoints() {
        return endpoints;
    }

    public ReachTracker getReachTracker() {
        return reachTracker;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Infers a rough URL path from the method ID.
     * e.g. "com.nova.user.web.UserController#findOrCreateUser(String, String)"
     *   →  "/user/findOrCreateUser"
     */
    private static String inferPath(String methodId) {
        int hash = methodId.lastIndexOf('#');
        int dot  = methodId.lastIndexOf('.', hash);
        if (hash < 0 || dot < 0) return "/" + methodId;

        String className  = methodId.substring(dot + 1, hash);
        String methodName = methodId.substring(hash + 1, methodId.indexOf('(', hash));

        // Strip "Controller" / "Resource" suffix from class name
        String resource = className
                .replace("Controller", "")
                .replace("Resource", "")
                .toLowerCase();

        return "/" + resource + "/" + methodName;
    }
}
