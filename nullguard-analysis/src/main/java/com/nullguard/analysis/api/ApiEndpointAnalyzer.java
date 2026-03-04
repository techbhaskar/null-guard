package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.APIFlowTrace;
import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.model.ReachData;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
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
        // Build a methodId → MethodModel index so we can resolve the MethodModel
        // for each trace entry point and use its annotation/modifier data.
        Map<String, MethodModel> methodIndex = buildMethodIndex(project);

        List<APIFlowTrace> traces = flowPathExtractor.extractDistinctPaths(
                project, config.getPropagationDepthLimit());

        List<ApiEndpointModel> built = new ArrayList<>();
        for (APIFlowTrace trace : traces) {
            reachTracker.track(trace);

            if (trace.getPath().isEmpty()) continue;

            String entryPoint = trace.getPath().get(0);
            MethodModel entryMethod = methodIndex.get(entryPoint);

            // Use annotation-aware overloads if MethodModel is available
            String httpMethod = (entryMethod != null)
                    ? FlowPathExtractor.inferHttpMethod(entryPoint, entryMethod)
                    : FlowPathExtractor.inferHttpMethod(entryPoint);
            String apiPath = (entryMethod != null)
                    ? FlowPathExtractor.inferPath(entryPoint, entryMethod)
                    : inferPathFromId(entryPoint);

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

    /** Builds a flat methodId → MethodModel index across the whole project. */
    private static Map<String, MethodModel> buildMethodIndex(ProjectModel project) {
        Map<String, MethodModel> index = new LinkedHashMap<>();
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel m : cls.getMethods().values()) {
                        String id = pkg.getPackageName() + "." + cls.getClassName()
                                    + "#" + m.getSignature();
                        index.put(id, m);
                    }
                }
            }
        }
        return index;
    }

    /**
     * Fallback path inference from the method ID only (no MethodModel available).
     * e.g. "com.nova.user.web.UserController#rejectVerification(UUID, ...)"
     *   →  "/user/rejectVerification"
     */
    private static String inferPathFromId(String methodId) {
        int hash = methodId.lastIndexOf('#');
        int dot  = methodId.lastIndexOf('.', hash);
        if (hash < 0 || dot < 0) return "/" + methodId;

        String className  = methodId.substring(dot + 1, hash);
        String methodName = methodId.substring(hash + 1, methodId.indexOf('(', hash));

        String resource = className
                .replace("Controller", "")
                .replace("Resource", "")
                .toLowerCase();

        return "/" + resource + "/" + methodName;
    }
}

