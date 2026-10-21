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
import java.util.Set;

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
     *
     * @param project   the fully-parsed project model
     * @param callEdges outgoing call edges from the pre-built GlobalCallGraph
     *                  ({@code GlobalCallGraph.getOutgoing()}) used for
     *                  inter-class edge traversal (controller → service → repo → ext)
     */
    public void build(ProjectModel project, Map<String, Set<String>> callEdges) {
        // Build a methodId → MethodModel and ClassModel index so we can resolve
        // both class-level @RequestMapping/@Path and method-level mapping metadata.
        Map<String, MethodModel> methodIndex = buildMethodIndex(project);
        Map<String, ClassModel> classIndex = buildClassIndex(project);

        List<APIFlowTrace> traces = flowPathExtractor.extractDistinctPaths(
                project, callEdges, config.getPropagationDepthLimit());

        reachTracker.reset();

        List<ApiEndpointModel> built = new ArrayList<>();
        for (APIFlowTrace trace : traces) {
            reachTracker.track(trace);

            if (trace.getPath().isEmpty()) continue;

            String entryPoint = trace.getPath().get(0);
            MethodModel entryMethod = methodIndex.get(entryPoint);
            ClassModel ownerClass = classIndex.get(entryPoint);

            // Use annotation-aware overloads if MethodModel is available
            String httpMethod = (entryMethod != null)
                    ? FlowPathExtractor.inferHttpMethod(entryPoint, entryMethod)
                    : FlowPathExtractor.inferHttpMethod(entryPoint);
            String apiPath = (entryMethod != null)
                    ? FlowPathExtractor.inferPath(entryPoint, ownerClass, entryMethod)
                    : inferPathFromId(entryPoint);

            if (entryMethod != null && !entryMethod.getHttpMethods().isEmpty()) {
                List<String> bases = ownerClass == null ? List.of("") : ownerClass.getBasePaths();
                for (String base : bases) for (String sub : entryMethod.getHttpPaths())
                    for (String verb : entryMethod.getHttpMethods()) {
                        String route = (base.replaceAll("/+$", "") + (sub.isEmpty() ? "" : "/" + sub.replaceAll("^/+", ""))).replaceAll("/{2,}", "/");
                        if (route.isEmpty()) route = "/";
                        built.add(new ApiEndpointModel(entryPoint, verb, route, trace.getPath()));
                    }
                continue;
            }
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
                        String id = com.nullguard.core.model.MethodIds.of(pkg, cls, m);
                        index.put(id, m);
                    }
                }
            }
        }
        return index;
    }

    /** Builds a flat methodId → ClassModel index across the whole project. */
    private static Map<String, ClassModel> buildClassIndex(ProjectModel project) {
        Map<String, ClassModel> index = new LinkedHashMap<>();
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel m : cls.getMethods().values()) {
                        String id = com.nullguard.core.model.MethodIds.of(pkg, cls, m);
                        index.put(id, cls);
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

