package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.APIFlowTrace;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FlowPathExtractor – detects REST API entry points and builds their downstream
 * propagation chains down to leaf methods using iterative DFS.
 *
 * <h3>API detection heuristics (no annotation processor required)</h3>
 * A method is treated as an API entry point when its containing class name ends
 * with {@code Controller}, or when the method name matches common REST naming
 * conventions ({@code get*}, {@code post*}, {@code put*}, {@code delete*},
 * {@code create*}, {@code update*}, {@code find*}, {@code fetch*}, {@code list*},
 * {@code search*}, {@code handle*}).
 *
 * <p>Additionally, if the CFG source text of the entry node contains any of the
 * Spring/JAX-RS annotation keywords ({@code @GetMapping}, {@code @PostMapping},
 * {@code @RequestMapping}, {@code @Path}, etc.) the method is unconditionally
 * included.
 *
 * <h3>Call-chain traversal</h3>
 * For each entry point the extractor performs an iterative DFS through the
 * {@link MethodCallInstruction}s extracted from each CFG, following the
 * callee chain until {@code depthLimit} or a leaf (no outgoing calls) is reached.
 * Cycles are broken with a per-path visited set.
 */
public class FlowPathExtractor {

    private final AnalysisConfig config;

    /** HTTP verb inferred from method name prefix. */
    private static final Map<String, String> VERB_MAP;
    static {
        VERB_MAP = new LinkedHashMap<>();
        VERB_MAP.put("get",    "GET");
        VERB_MAP.put("find",   "GET");
        VERB_MAP.put("fetch",  "GET");
        VERB_MAP.put("list",   "GET");
        VERB_MAP.put("search", "GET");
        VERB_MAP.put("post",   "POST");
        VERB_MAP.put("create", "POST");
        VERB_MAP.put("add",    "POST");
        VERB_MAP.put("put",    "PUT");
        VERB_MAP.put("update", "PUT");
        VERB_MAP.put("patch",  "PATCH");
        VERB_MAP.put("delete", "DELETE");
        VERB_MAP.put("remove", "DELETE");
        VERB_MAP.put("handle", "UNKNOWN");
        VERB_MAP.put("process","UNKNOWN");
        VERB_MAP.put("approve","PUT");
        VERB_MAP.put("verify", "POST");
        VERB_MAP.put("request","POST");
        VERB_MAP.put("send",   "POST");
    }

    /** Spring / JAX-RS mapping annotation keywords. */
    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping",
            "@PatchMapping", "@RequestMapping", "@Path", "@GET", "@POST",
            "@PUT", "@DELETE", "@PATCH"
    );

    public FlowPathExtractor(AnalysisConfig config) {
        this.config = config;
    }

    /**
     * Scans the ProjectModel for API entry points and builds downstream paths.
     *
     * @param projectModel the fully-parsed project
     * @param depthLimit   maximum chain depth
     * @return list of {@link APIFlowTrace} objects, one per discovered entry point
     */
    public List<APIFlowTrace> extractDistinctPaths(ProjectModel projectModel, int depthLimit) {
        // ── Step 1: build a method-id → MethodModel index ────────────────────
        Map<String, MethodModel> methodIndex = buildMethodIndex(projectModel);

        // ── Step 2: build a simple callee map from MethodCallInstructions ──
        // Key = callerMethodId, Value = set of callee method names resolved to full IDs
        Map<String, List<String>> calleeMap = buildCalleeMap(methodIndex);

        // ── Step 3: find API entry points ────────────────────────────────────
        List<String> entryPoints = new ArrayList<>();
        for (Map.Entry<String, MethodModel> entry : methodIndex.entrySet()) {
            if (isApiEntryPoint(entry.getKey(), entry.getValue())) {
                entryPoints.add(entry.getKey());
            }
        }
        Collections.sort(entryPoints); // deterministic order

        // ── Step 4: DFS from each entry point to build propagation chain ──────
        List<APIFlowTrace> traces = new ArrayList<>();
        for (String entry : entryPoints) {
            List<String> chain = buildChain(entry, calleeMap, depthLimit);
            // Chain includes the entry point itself as first element
            APIFlowTrace trace = new APIFlowTrace(chain);
            traces.add(trace);
        }
        return Collections.unmodifiableList(traces);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Build a flat map of methodId → MethodModel across the whole project. */
    private Map<String, MethodModel> buildMethodIndex(ProjectModel project) {
        Map<String, MethodModel> index = new LinkedHashMap<>();
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel mth : cls.getMethods().values()) {
                        String id = pkg.getPackageName() + "." + cls.getClassName()
                                    + "#" + mth.getSignature();
                        index.put(id, mth);
                    }
                }
            }
        }
        return index;
    }

    /**
     * Build a callee-name map from MethodCallInstructions extracted from CFGs.
     * Because we only have method names (not fully qualified), we map by short name
     * and resolve to full IDs by fuzzy match on the method index.
     */
    private Map<String, List<String>> buildCalleeMap(Map<String, MethodModel> methodIndex) {
        // Pre-build a shortName → [fullId] map for fast resolution
        Map<String, List<String>> nameToIds = new LinkedHashMap<>();
        for (String id : methodIndex.keySet()) {
            String shortName = shortName(id);
            nameToIds.computeIfAbsent(shortName, k -> new ArrayList<>()).add(id);
        }

        com.nullguard.analysis.extractor.BasicInstructionExtractor extractor =
                new com.nullguard.analysis.extractor.BasicInstructionExtractor();

        Map<String, List<String>> calleeMap = new LinkedHashMap<>();
        for (Map.Entry<String, MethodModel> entry : methodIndex.entrySet()) {
            String callerId = entry.getKey();
            MethodModel mth = entry.getValue();
            List<String> callees = new ArrayList<>();
            calleeMap.put(callerId, callees);

            mth.getControlFlowModel().ifPresent(cfg -> {
                for (com.nullguard.analysis.ir.Instruction inst : extractor.extract(cfg)) {
                    if (inst instanceof com.nullguard.analysis.ir.MethodCallInstruction call) {
                        String callee = call.methodCall();
                        List<String> matched = nameToIds.get(callee);
                        if (matched != null) {
                            callees.addAll(matched);
                        }
                    }
                }
            });
        }
        return calleeMap;
    }

    /**
     * Iterative DFS: builds the propagation chain from the entry point down.
     * Returns an ordered list from entry → ... → leaf, cycle-safe.
     */
    private List<String> buildChain(String entryPoint,
                                    Map<String, List<String>> calleeMap,
                                    int depthLimit) {
        List<String> chain   = new ArrayList<>();
        Set<String>  visited = new LinkedHashSet<>();

        record Frame(String id, int depth) {}
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(entryPoint, 0));

        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            if (frame.depth() > depthLimit || visited.contains(frame.id())) continue;
            visited.add(frame.id());
            chain.add(frame.id());

            List<String> callees = calleeMap.getOrDefault(frame.id(), Collections.emptyList());
            // Push in reverse order so natural alphabetical order is preserved
            List<String> sorted = new ArrayList<>(callees);
            Collections.sort(sorted, Collections.reverseOrder());
            for (String callee : sorted) {
                if (!visited.contains(callee)) {
                    stack.push(new Frame(callee, frame.depth() + 1));
                }
            }
        }
        return chain;
    }

    /** Extracts the method name from a canonical method ID. */
    private static String shortName(String methodId) {
        int hash = methodId.lastIndexOf('#');
        int paren = methodId.indexOf('(', hash);
        if (hash >= 0 && paren > hash) {
            return methodId.substring(hash + 1, paren);
        }
        return methodId;
    }

    /** Infers the HTTP verb from the method name prefix. */
    public static String inferHttpMethod(String methodId) {
        String name = shortName(methodId).toLowerCase();
        for (Map.Entry<String, String> e : VERB_MAP.entrySet()) {
            if (name.startsWith(e.getKey())) return e.getValue();
        }
        return "UNKNOWN";
    }

    /**
     * Returns {@code true} when the method should be treated as an API entry point:
     * <ul>
     *   <li>Its class name ends with {@code Controller} or {@code Resource}</li>
     *   <li>OR the method signature starts with a REST verb prefix</li>
     *   <li>OR the CFG entry node contains a mapping annotation keyword</li>
     * </ul>
     */
    private static boolean isApiEntryPoint(String methodId, MethodModel method) {
        // 1. Controller class suffix
        int hash = methodId.lastIndexOf('#');
        int dot  = methodId.lastIndexOf('.', hash);
        if (hash > 0 && dot >= 0) {
            String className = methodId.substring(dot + 1, hash);
            if (className.endsWith("Controller") || className.endsWith("Resource")) {
                return true;
            }
        }

        // 2. Method name prefix heuristic
        String name = shortName(methodId).toLowerCase();
        for (String prefix : VERB_MAP.keySet()) {
            if (name.startsWith(prefix)) return true;
        }

        // 3. Annotation keyword in CFG source text
        return method.getControlFlowModel()
                .map(cfg -> cfg.getNodes().values().stream()
                        .anyMatch(node -> {
                            String src = node.getSourceCode();
                            return MAPPING_ANNOTATIONS.stream().anyMatch(src::contains);
                        }))
                .orElse(false);
    }
}
