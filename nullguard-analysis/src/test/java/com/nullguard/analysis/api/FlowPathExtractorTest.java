package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.core.cfg.*;
import com.nullguard.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FlowPathExtractorTest {
    private final FlowPathExtractor extractor = new FlowPathExtractor(new AnalysisConfig(10, .85, 1, 60, 2));

    @Test void endpointAnalyzerSupportsLegacyAnnotationsAndResetsBetweenBuilds() {
        var analyzer = new ApiEndpointAnalyzer(new AnalysisConfig(10, .85, 1, 60, 2));
        assertTrue(analyzer.getEndpoints().isEmpty());
        var project = project(method("create", "@PostMapping(\"orders\") public void create()", ""));
        analyzer.build(project, Map.of());
        assertEquals(1, analyzer.getEndpoints().size());
        assertEquals("POST", analyzer.getEndpoints().get(0).getHttpMethod());
        assertEquals("/orders", analyzer.getEndpoints().get(0).getPath());
        assertEquals(1, analyzer.getReachTracker().applyTo(project));
        analyzer.build(project(), Map.of());
        assertTrue(analyzer.getEndpoints().isEmpty());
        assertTrue(analyzer.getReachTracker().getReachMap().isEmpty());
        var bounded = new ApiEndpointAnalyzer(new AnalysisConfig(-1, .85, 1, 60, 2));
        bounded.build(project, Map.of());
        assertTrue(bounded.getEndpoints().isEmpty());
    }

    @Test void endpointAnalyzerExpandsEveryBasePathVerbAndSubpathCombination() {
        var endpoint = MethodModel.builder().methodName("mapped").signature("mapped()")
                .httpMethods(List.of("GET", "POST")).httpPaths(List.of("", "//item")).build();
        var owner = ClassModel.builder().className("Api").basePaths(List.of("", "/v1/"))
                .addMethod(endpoint).build();
        var project = ProjectModel.builder().projectName("test").addModule(ModuleModel.builder().moduleName("main")
                .addPackage(PackageModel.builder().packageName("demo").addClass(owner).build()).build()).build();
        var analyzer = new ApiEndpointAnalyzer(new AnalysisConfig(10, .85, 1, 60, 2));
        analyzer.build(project, Map.of());
        assertEquals(8, analyzer.getEndpoints().size());
        assertEquals(Set.of("GET /", "POST /", "GET /item", "POST /item", "GET /v1", "POST /v1", "GET /v1/item", "POST /v1/item"),
                analyzer.getEndpoints().stream().map(e -> e.getHttpMethod() + " " + e.getPath()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void resolvedPathsAreSortedBoundedAndBreakCycles() {
        var project = project(method("getOrders", "@GetMapping public void getOrders()", ""),
                method("hidden", "@PostMapping private void hidden()", ""),
                method("helper", "public void helper()", ""));
        String entry = "demo.Api#getOrders()";
        var edges = Map.of(entry, Set.of("z", "a"), "a", Set.of(entry, "leaf"), "z", Set.of("leaf"));
        assertEquals(List.of(entry, "a", "leaf", "z"), extractor.extractDistinctPaths(project, edges, 10).get(0).getPath());
        var shallow = extractor.extractDistinctPaths(project, edges, 0);
        assertEquals(1, shallow.size());
        assertEquals(List.of(entry), shallow.get(0).getPath());
        assertThrows(UnsupportedOperationException.class, () -> shallow.clear());
        assertTrue(extractor.extractDistinctPaths(project, edges, -1).get(0).getPath().isEmpty());
    }

    @Test @SuppressWarnings("deprecation") void legacyPathsResolveCallsAndIgnoreUnknownTargets() {
        var project = project(method("getOrders", "@GetMapping public void getOrders()", "service.load();"),
                method("load", "public void load()", "missing();"),
                method("ignored", "@GET protected void ignored()", ""));
        assertEquals(List.of("demo.Api#getOrders()", "demo.Api#load()"),
                extractor.extractDistinctPaths(project, 10).get(0).getPath());
        assertEquals(List.of("demo.Api#getOrders()"), extractor.extractDistinctPaths(project, 0).get(0).getPath());
        assertTrue(extractor.extractDistinctPaths(project, -1).get(0).getPath().isEmpty());
    }

    @Test void annotationsOverrideNameHeuristics() {
        var annotations = Map.of("@GetMapping", "GET", "@PostMapping", "POST", "@PutMapping", "PUT",
                "@DeleteMapping", "DELETE", "@PatchMapping", "PATCH", "@RequestMapping", "UNKNOWN");
        annotations.forEach((annotation, verb) -> assertEquals(verb,
                FlowPathExtractor.inferHttpMethod("demo.Api#delete()", method("delete", annotation, ""))));
        assertEquals("HEAD", FlowPathExtractor.inferHttpMethod("x", MethodModel.builder().methodName("x")
                .signature("x()").httpMethod("HEAD").build()));
        Map.of("find", "GET", "create", "POST", "update", "PUT", "patch", "PATCH", "remove", "DELETE", "unknown", "UNKNOWN")
                .forEach((name, verb) -> {
                    assertEquals(verb, FlowPathExtractor.inferHttpMethod("demo.Api#" + name + "()"));
                    assertEquals(verb, FlowPathExtractor.inferHttpMethod(name, method(name, "", "")));
                });
    }

    @Test void pathsComposeClassAndMethodMappingsWithoutDuplicatingPrefixes() {
        var owner = ClassModel.builder().className("Api").basePath(" api/ ").build();
        assertEquals("/api/orders", FlowPathExtractor.inferPath("get", owner, method("get", "@GetMapping(\"orders\")", "")));
        assertEquals("/api", FlowPathExtractor.inferPath("get", owner, method("get", "@GET", "")));
        assertEquals("/api", FlowPathExtractor.inferPath("get", owner, mapped("/")));
        assertEquals("/api/orders", FlowPathExtractor.inferPath("get", owner, mapped("/api/orders")));
        assertEquals("/api", FlowPathExtractor.inferPath("get", owner, mapped("/api")));
        assertEquals("/orders", FlowPathExtractor.inferPath("get", null, mapped("orders")));
        assertEquals("/get", FlowPathExtractor.inferPath("demo.Api#get()", method("get", "", "")));
        assertEquals("/orders", FlowPathExtractor.inferPath("get", method("get", "@GetMapping(path = \"orders\")", "")));
    }

    private static MethodModel mapped(String path) {
        return MethodModel.builder().methodName("get").signature("get()").httpPath(path).build();
    }

    private static MethodModel method(String name, String declaration, String statement) {
        var nodes = new LinkedHashMap<String, ControlFlowNode>();
        nodes.put("entry", new ControlFlowNode("entry", NodeType.ENTRY, declaration, 1));
        nodes.put("body", new ControlFlowNode("body", NodeType.STATEMENT, statement, 2));
        return MethodModel.builder().methodName(name).signature(name + "()")
                .controlFlowModel(new ControlFlowModel(name, nodes, new LinkedHashSet<>(), "entry", "body")).build();
    }

    private static ProjectModel project(MethodModel... methods) {
        var owner = ClassModel.builder().className("Api");
        for (var method : methods) owner.addMethod(method);
        return ProjectModel.builder().projectName("test").addModule(ModuleModel.builder().moduleName("main")
                .addPackage(PackageModel.builder().packageName("demo").addClass(owner.build()).build()).build()).build();
    }
}
