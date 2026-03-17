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
 * A method is treated as an API entry point when <strong>either</strong>:
 * <ol>
 *   <li>Its containing class name ends with {@code Controller} or {@code Resource}
 *       (the canonical Spring/JAX-RS naming convention), <strong>or</strong></li>
 *   <li>Any CFG node's source text contains a Spring/JAX-RS mapping annotation keyword
 *       ({@code @GetMapping}, {@code @PostMapping}, {@code @RequestMapping}, etc.).</li>
 * </ol>
 *
 * <p><strong>Note:</strong> method-name prefix heuristics ({@code get*}, {@code find*},
 * {@code update*}, etc.) are intentionally <em>not</em> used for entry point detection
 * because they produce too many false positives (repositories, mappers, clients, services).
 * The VERB_MAP is still used to infer the HTTP verb for display purposes only.
 *
 * <h3>Call-chain traversal</h3>
 * For each entry point the extractor performs an iterative DFS through the
 * {@code MethodCallInstruction}s extracted from each CFG, following the
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
     * <p>Uses the pre-resolved call graph edges so that cross-class calls
     * (controller → service → repository → external) are traced correctly.
     *
     * @param projectModel the fully-parsed project
     * @param callEdges    outgoing call edges from the GlobalCallGraph
     *                     ({@code GlobalCallGraph.getOutgoing()})
     * @param depthLimit   maximum chain depth
     * @return list of {@link APIFlowTrace} objects, one per discovered entry point
     */
    public List<APIFlowTrace> extractDistinctPaths(ProjectModel projectModel,
                                                    Map<String, Set<String>> callEdges,
                                                    int depthLimit) {
        // ── Step 1: build a method-id → MethodModel index ────────────────────
        Map<String, MethodModel> methodIndex = buildMethodIndex(projectModel);

        // ── Step 2: find API entry points ─────────────────────────────────────
        List<String> entryPoints = new ArrayList<>();
        for (Map.Entry<String, MethodModel> entry : methodIndex.entrySet()) {
            if (isApiEntryPoint(entry.getKey(), entry.getValue())) {
                entryPoints.add(entry.getKey());
            }
        }
        Collections.sort(entryPoints); // deterministic order

        // ── Step 3: DFS from each entry point using the pre-resolved edges ────
        // callEdges correctly captures controller → service → repository → ext
        // because BasicCallGraphBuilder uses MethodResolver for cross-class lookup.
        List<APIFlowTrace> traces = new ArrayList<>();
        for (String entry : entryPoints) {
            List<String> chain = buildChain(entry, callEdges, depthLimit);
            // Chain includes the entry point itself as first element
            APIFlowTrace trace = new APIFlowTrace(chain);
            traces.add(trace);
        }
        return Collections.unmodifiableList(traces);
    }

    /**
     * Backward-compatible overload (no call graph): falls back to the old
     * name-based callee resolution (less accurate for cross-class calls).
     *
     * @deprecated Prefer the overload that accepts pre-resolved call edges.
     */
    @Deprecated
    public List<APIFlowTrace> extractDistinctPaths(ProjectModel projectModel, int depthLimit) {
        // ── Step 1: build a method-id → MethodModel index ────────────────────
        Map<String, MethodModel> methodIndex = buildMethodIndex(projectModel);

        // ── Step 2: build a simple callee map from MethodCallInstructions ──
        Map<String, List<String>> calleeMap = buildCalleeMap(methodIndex);

        // ── Step 3: find API entry points ────────────────────────────────────
        List<String> entryPoints = new ArrayList<>();
        for (Map.Entry<String, MethodModel> entry : methodIndex.entrySet()) {
            if (isApiEntryPoint(entry.getKey(), entry.getValue())) {
                entryPoints.add(entry.getKey());
            }
        }
        Collections.sort(entryPoints);

        // ── Step 4: DFS from each entry point ────────────────────────────────
        List<APIFlowTrace> traces = new ArrayList<>();
        for (String entry : entryPoints) {
            List<String> chain = buildChainFromMap(entry, calleeMap, depthLimit);
            traces.add(new APIFlowTrace(chain));
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
                        String calleeString = call.methodCall();
                        // Strip receiver and possible leftovers to get the bare method name
                        String searchName = calleeString;
                        if (searchName.contains(".")) {
                            searchName = searchName.substring(searchName.lastIndexOf(".") + 1);
                        }
                        if (searchName.contains("(")) {
                            searchName = searchName.substring(0, searchName.indexOf("("));
                        }
                        
                        List<String> matched = nameToIds.get(searchName.trim());
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
     * Iterative DFS using pre-resolved call edges: follows
     * controller → service → repository → external nodes.
     * External nodes (ext#...) are included so callers can see the full reach.
     */
    private List<String> buildChain(String entryPoint,
                                    Map<String, Set<String>> callEdges,
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

            Set<String> callees = callEdges.getOrDefault(frame.id(), Collections.emptySet());
            // Sort for deterministic output
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

    /** Legacy map-based DFS (used by the deprecated overload). */
    private List<String> buildChainFromMap(String entryPoint,
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

    /**
     * Infers the HTTP verb from the entry node source text (annotation-first),
     * falling back to method-name prefix heuristics.
     */
    public static String inferHttpMethod(String methodId, MethodModel method) {
        String entrySource = getEntrySource(method);
        // Annotation-based detection (most reliable)
        if (entrySource.contains("@GetMapping")    || entrySource.contains("@GET"))    return "GET";
        if (entrySource.contains("@PostMapping")   || entrySource.contains("@POST"))   return "POST";
        if (entrySource.contains("@PutMapping")    || entrySource.contains("@PUT"))    return "PUT";
        if (entrySource.contains("@DeleteMapping") || entrySource.contains("@DELETE")) return "DELETE";
        if (entrySource.contains("@PatchMapping")  || entrySource.contains("@PATCH"))  return "PATCH";
        if (entrySource.contains("@RequestMapping")) return "UNKNOWN"; // verb unspecified
        // Fallback: method name prefix
        String name = shortName(methodId).toLowerCase();
        for (Map.Entry<String, String> e : VERB_MAP.entrySet()) {
            if (name.startsWith(e.getKey())) return e.getValue();
        }
        return "UNKNOWN";
    }

    /** Backward-compat overload (used from ApiEndpointAnalyzer via methodId only) */
    public static String inferHttpMethod(String methodId) {
        String name = shortName(methodId).toLowerCase();
        for (Map.Entry<String, String> e : VERB_MAP.entrySet()) {
            if (name.startsWith(e.getKey())) return e.getValue();
        }
        return "UNKNOWN";
    }

    /**
     * Extracts the actual URL path from a mapping annotation value, e.g.
     * {@code @PostMapping("/{id}/reject")} → {@code "/{id}/reject"}.
     * Falls back to {@code "/" + methodName} if no annotation value is found.
     */
    public static String inferPath(String methodId, MethodModel method) {
        String entrySource = getEntrySource(method);
        // Try to find annotation value string: @XxxMapping("...value...")
        java.util.regex.Matcher m = ANNOTATION_PATH_PATTERN.matcher(entrySource);
        if (m.find()) {
            String rawPath = m.group(1);
            // Strip quotes if present
            rawPath = rawPath.replaceAll("^\"|\"$", "");
            return rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        }
        // No annotation path found — derive from method name
        String name = shortName(methodId);
        return "/" + name;
    }

    /** Pattern to capture the string value inside a mapping annotation. */
    private static final java.util.regex.Pattern ANNOTATION_PATH_PATTERN =
        java.util.regex.Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    /** Returns the entry node's source text (annotations + modifiers), or empty string. */
    private static String getEntrySource(MethodModel method) {
        return method.getControlFlowModel()
                .map(cfg -> {
                    String entryId = cfg.getEntryNodeId();
                    com.nullguard.core.cfg.ControlFlowNode n = cfg.getNodes().get(entryId);
                    return n != null && n.getSourceCode() != null ? n.getSourceCode() : "";
                })
                .orElse("");
    }

    /**
     * Returns {@code true} when the method should be treated as a REST API entry point.
     *
     * <p><strong>Rules:</strong>
     * <ol>
     *   <li><strong>Visibility filter first:</strong> {@code private} and {@code protected}
     *       methods are unconditionally excluded — they cannot be HTTP endpoints.</li>
     *   <li>The containing class name ends with {@code Controller} or {@code Resource}
     *       AND the method is {@code public}.</li>
     *   <li>Any mapping annotation keyword is present in the entry node source text
     *       ({@code @GetMapping}, {@code @PostMapping}, {@code @RequestMapping}, etc.).</li>
     * </ol>
     */
    private static boolean isApiEntryPoint(String methodId, MethodModel method) {
        String entrySource = getEntrySource(method);

        // ── Rule 0: Skip private / protected methods ──────────────────────────
        // Only public methods can be real HTTP endpoints.
        if (entrySource.contains("private ") || entrySource.contains("protected ")) {
            return false;
        }

        // ── Rule 1: Has a mapping annotation (Required) ───────────────────────
        // We now strictly require an annotation to avoid false positives 
        // with internal helper methods in Controller classes.
        return MAPPING_ANNOTATIONS.stream().anyMatch(entrySource::contains);
    }

}
