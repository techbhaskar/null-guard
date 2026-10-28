package com.nullguard.bootstrap;

import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.analysis.orchestrator.AnalysisOrchestrator;
import com.nullguard.callgraph.builder.BasicCallGraphBuilder;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.parser.JavaParserAstParser;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.scoring.propagation.FixpointRiskPropagationEngine;
import com.nullguard.scoring.scoring.DefaultStabilityScorer;
import com.nullguard.suggestions.engine.DefaultSuggestionEngine;
import com.nullguard.visualization.export.DotGraphExporter;
import com.nullguard.visualization.export.JsonGraphExporter;
import com.nullguard.visualization.graph.DefaultPropagationGraphBuilder;
import com.nullguard.visualization.model.PropagationGraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AnalysisPipeline â€“ executes the full NullGuard analysis in a single,
 * deterministic, sequential pass.
 *
 * <p><strong>Execution order (frozen):</strong>
 * <ol>
 *   <li>Parse project â†’ ProjectModel (core)</li>
 *   <li>Build Global Call Graph (callgraph)</li>
 *   <li>Run Analysis Orchestrator: null-state + CFG + contracts + APIs + hotspots (analysis)</li>
 *   <li>Run Fixpoint Risk Propagation (scoring)</li>
 *   <li>Compute Stability Index / ProjectRiskSummary (scoring)</li>
 *   <li>Generate Suggestions (suggestions)</li>
 *   <li>Build Propagation Graph (visualization)</li>
 *   <li>Export JSON + DOT artifacts (visualization)</li>
 *   <li>Run architecture integrity validation</li>
 *   <li>Assemble FinalAnalysisResult</li>
 * </ol>
 *
 * <p>No module calls another directly. All cross-module communication flows
 * through this class via the {@link PipelineContext}.
 */
public final class AnalysisPipeline {

    // â”€â”€ Injected module singletons â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private final JavaParserAstParser             coreParser;
    private final BasicCallGraphBuilder           callGraphBuilder;
    private final AnalysisOrchestrator            analysisOrchestrator;
    private final FixpointRiskPropagationEngine   riskPropagationEngine;
    private final DefaultStabilityScorer          stabilityScorer;
    private final DefaultSuggestionEngine         suggestionEngine;
    private final DefaultPropagationGraphBuilder  propagationGraphBuilder;
    private final JsonGraphExporter               jsonGraphExporter;
    private final DotGraphExporter                dotGraphExporter;
    private final ResultAssembler                 resultAssembler;
    private final ArchitectureValidator           architectureValidator;

    /**
     * Constructor injection â€“ all dependencies are wired by {@link EngineBootstrap}.
     */
    AnalysisPipeline(
            JavaParserAstParser            coreParser,
            BasicCallGraphBuilder          callGraphBuilder,
            AnalysisOrchestrator           analysisOrchestrator,
            FixpointRiskPropagationEngine  riskPropagationEngine,
            DefaultStabilityScorer         stabilityScorer,
            DefaultSuggestionEngine        suggestionEngine,
            DefaultPropagationGraphBuilder propagationGraphBuilder,
            JsonGraphExporter              jsonGraphExporter,
            DotGraphExporter               dotGraphExporter,
            ResultAssembler                resultAssembler,
            ArchitectureValidator          architectureValidator) {

        this.coreParser             = Objects.requireNonNull(coreParser);
        this.callGraphBuilder       = Objects.requireNonNull(callGraphBuilder);
        this.analysisOrchestrator   = Objects.requireNonNull(analysisOrchestrator);
        this.riskPropagationEngine  = Objects.requireNonNull(riskPropagationEngine);
        this.stabilityScorer        = Objects.requireNonNull(stabilityScorer);
        this.suggestionEngine       = Objects.requireNonNull(suggestionEngine);
        this.propagationGraphBuilder = Objects.requireNonNull(propagationGraphBuilder);
        this.jsonGraphExporter      = Objects.requireNonNull(jsonGraphExporter);
        this.dotGraphExporter       = Objects.requireNonNull(dotGraphExporter);
        this.resultAssembler        = Objects.requireNonNull(resultAssembler);
        this.architectureValidator  = Objects.requireNonNull(architectureValidator);
    }

    /**
     * Execute the full analysis pipeline for a given source root.
     *
     * @param projectRoot path to the project source directory
     * @param config      unified NullGuard configuration (loaded once, passed everywhere)
     * @return assembled {@link FinalAnalysisResult}
     */
    public FinalAnalysisResult execute(Path projectRoot, NullGuardConfig config) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        Objects.requireNonNull(config, "config must not be null");

        PipelineContext ctx = new PipelineContext(config);
        ctx.getTiming().markPipelineStart();

        // â”€â”€ Step 1: Parse â†’ ProjectModel â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        ProjectModel projectModel = timed(ctx, "parse",
                () -> coreParser.parse(projectRoot));
        ctx.setProjectModel(projectModel);

        // â”€â”€ Step 2: Build Global Call Graph â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        GlobalCallGraph callGraph = timed(ctx, "callgraph",
                () -> callGraphBuilder.build(projectModel));
        ctx.setCallGraph(callGraph);

        // â”€â”€ Step 3: Analysis Orchestrator (null-state, CFG, contracts, APIs, hotspots) â”€â”€
        // Pass the outgoing call edges so FlowPathExtractor can resolve
        // controller â†’ service â†’ repository â†’ external chains correctly.
        timed(ctx, "analysis", () -> {
            analysisOrchestrator.analyze(projectModel, callGraph.getOutgoing());
            return null;
        });

        // â”€â”€ Step 3.5: Extract API Endpoints (needed for v1.1 Blast Radius scoring) â”€â”€
        List<ApiEndpointModel> apiEndpoints = timed(ctx, "api-endpoints",
                () -> new ArrayList<>(analysisOrchestrator.getApiEndpointAnalyzer().getEndpoints()));
        ctx.setApiEndpoints(apiEndpoints);

        // â”€â”€ Step 4: Fixpoint Risk Propagation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Map<String, AdjustedRiskModel> riskMap = timed(ctx, "risk-propagation",
                () -> riskPropagationEngine.propagate(projectModel, callGraph, config.toScoringConfig(), apiEndpoints));
        ctx.setAdjustedRiskMap(riskMap);
        analysisOrchestrator.getHotspotDetector().detect(projectModel);
        var hotspotMethods = analysisOrchestrator.getHotspotDetector().getArchitecturalHotspots().stream()
                .map(ArchitecturalHotspot::getMethodRef).collect(java.util.stream.Collectors.toSet());
        for (ApiEndpointModel endpoint : apiEndpoints) {
            double endpointRisk = endpoint.getPropagationChain().stream()
                    .map(riskMap::get).filter(Objects::nonNull)
                    .mapToDouble(AdjustedRiskModel::getAdjustedRisk).max().orElse(0.0);
            endpoint.setApiRiskScore(endpointRisk);
            endpoint.setHotspotIndicators(endpoint.getPropagationChain().stream().anyMatch(hotspotMethods::contains)
                    ? List.of("ARCHITECTURAL_HOTSPOT") : List.of());
        }

        // Store per-method risk contributor explanations for dashboard rendering
        if (riskPropagationEngine instanceof com.nullguard.scoring.propagation.FixpointRiskPropagationEngine) {
            com.nullguard.scoring.propagation.FixpointRiskPropagationEngine fpe =
                (com.nullguard.scoring.propagation.FixpointRiskPropagationEngine) riskPropagationEngine;
            ctx.setRiskReasonMap(fpe.getRiskContributors());
        }

        // â”€â”€ Step 5: Stability Scoring â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        ProjectRiskSummary riskSummary = timed(ctx, "scoring",
                () -> stabilityScorer.score(riskMap, callGraph, config.toScoringConfig()));
        ctx.setRiskSummary(riskSummary);


        // â”€â”€ Step 5b: Collect Hotspots from Analysis result â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        List<ArchitecturalHotspot> hotspots = timed(ctx, "hotspots",
                () -> new ArrayList<>(analysisOrchestrator.getHotspotDetector().getArchitecturalHotspots()));
        ctx.setHotspots(hotspots);

        // â”€â”€ Step 6: Generate Suggestions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        List<com.nullguard.suggestions.model.Suggestion> suggestions = timed(ctx, "suggestions",
                () -> suggestionEngine.generate(projectModel, riskMap, callGraph));
        ctx.setSuggestions(suggestions);

        // â”€â”€ Step 7: Build Propagation Graph â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        PropagationGraph propGraph = timed(ctx, "visualization-graph",
                () -> propagationGraphBuilder.build(projectModel, callGraph, riskMap));
        ctx.setPropagationGraph(propGraph);

        // â”€â”€ Step 8: Export Visualization, OpenAPI, Static Analysis & CVE Artifacts â”€â”€
        VisualizationBundle vizBundle = timed(ctx, "visualization-export", () -> {
            String dot  = dotGraphExporter.export(propGraph);
            Map<String, Object> openApiSpec = OpenApiSpecGenerator.generate(projectModel, apiEndpoints, riskMap);
            var options = config.getOpenApiOptions();
            @SuppressWarnings("unchecked")
            var info = (Map<String, Object>) openApiSpec.get("info");
            if (options.specFields().get("info") instanceof Map<?, ?> configuredInfo)
                configuredInfo.forEach((key, value) -> info.put(key.toString(), value));
            for (String key : List.of("servers", "security"))
                if (options.specFields().containsKey(key)) openApiSpec.put(key, options.specFields().get(key));
            @SuppressWarnings("unchecked")
            var components = new java.util.LinkedHashMap<>((Map<String, Object>) openApiSpec.get("components"));
            if (options.specFields().containsKey("securitySchemes")) components.put("securitySchemes", options.specFields().get("securitySchemes"));
            openApiSpec.put("components", components);
            openApiSpec.put("x-nullguard-try-it-out", options.tryItOut());
            List<Finding> staticFindings = StaticCodeAnalyzer.analyze(projectModel, callGraph, apiEndpoints);
            Map<String, Object> archSummary = StaticCodeAnalyzer.buildSummary(staticFindings);
            ReachableCveScanner.ScanOutcome cveOutcome = ReachableCveScanner.scan(
                    projectRoot, config, projectModel, callGraph, apiEndpoints);
            Map<String, Object> sastSummary = SastScanner.scan(config);

            List<Finding> additionalFindings = new ArrayList<>(staticFindings.size() + cveOutcome.findings().size());
            additionalFindings.addAll(staticFindings);
            additionalFindings.addAll(cveOutcome.findings());

            FindingExporter exporter = new FindingExporter();
            List<Finding> findings = exporter.collect(projectModel, hotspots, suggestions, additionalFindings);
            // Keep the report as an object model and stream it at write time. Rendering it to
            // an indented String, re-parsing it and rendering again held ~4 copies of the report
            // in memory and caused OutOfMemoryError on real services.
            java.util.Map<String, Object> report = JsonGraphExporter.report(propGraph, riskSummary);
            report.put("schemaVersion", "1.0");
            report.put("analysisCompleteness", AnalysisCompleteness.describe(projectModel));
            report.put("findings", List.copyOf(findings));
            report.put("apiEndpoints", List.copyOf(apiEndpoints));
            report.put("hotspots", List.copyOf(hotspots));
            report.put("suggestions", List.copyOf(suggestions));
            report.put("openApiSpec", openApiSpec);
            report.put("architectureSummary", archSummary);
            report.put("memoryInsights", MemoryInsights.describe(projectModel));
            report.put("cveSummary", cveOutcome.cveSummary());
            report.put("sastSummary", sastSummary);
            return new VisualizationBundle(report, jsonGraphExporter, dot, exporter.sarif(findings, projectRoot), findings);
        });
        ctx.setVisualizationBundle(vizBundle);

        // â”€â”€ Step 9: Architecture Integrity Validation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        timed(ctx, "validation", () -> {
            architectureValidator.validateArchitectureIntegrity(ctx);
            architectureValidator.validateNoCircularDependencies(ctx);
            architectureValidator.validateDeterministicOrdering(ctx);
            return null;
        });

        // â”€â”€ Step 10: Assemble Final Result â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        ctx.getTiming().markPipelineEnd();
        return resultAssembler.assemble(ctx);
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€


    /**
     * Executes a pipeline step, records its wall-clock duration, and returns the result.
     */
    @SuppressWarnings("unchecked")
    private <T> T timed(PipelineContext ctx, String stepName, StepSupplier<T> step) {
        long start = System.currentTimeMillis();
        T result = step.execute();
        ctx.getTiming().recordStep(stepName, System.currentTimeMillis() - start);
        return result;
    }

    @FunctionalInterface
    private interface StepSupplier<T> {
        T execute();
    }
}
