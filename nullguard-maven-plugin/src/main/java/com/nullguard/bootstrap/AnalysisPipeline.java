package com.nullguard.bootstrap;

import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.analysis.model.ReachData;
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
 * AnalysisPipeline – executes the full NullGuard analysis in a single,
 * deterministic, sequential pass.
 *
 * <p><strong>Execution order (frozen):</strong>
 * <ol>
 *   <li>Parse project → ProjectModel (core)</li>
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

    // ── Injected module singletons ───────────────────────────────────────────

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
     * Constructor injection – all dependencies are wired by {@link EngineBootstrap}.
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

        // ── Step 1: Parse → ProjectModel ────────────────────────────────────
        ProjectModel projectModel = timed(ctx, "parse",
                () -> coreParser.parse(projectRoot));
        ctx.setProjectModel(projectModel);

        // ── Step 2: Build Global Call Graph ──────────────────────────────────
        GlobalCallGraph callGraph = timed(ctx, "callgraph",
                () -> callGraphBuilder.build(projectModel));
        ctx.setCallGraph(callGraph);

        // ── Step 3: Analysis Orchestrator (null-state, CFG, contracts, APIs, hotspots) ──
        timed(ctx, "analysis", () -> {
            analysisOrchestrator.analyze(projectModel);
            return null;
        });

        // ── Step 4: Fixpoint Risk Propagation ────────────────────────────────
        Map<String, AdjustedRiskModel> riskMap = timed(ctx, "risk-propagation",
                () -> riskPropagationEngine.propagate(projectModel, callGraph, config.toScoringConfig()));
        ctx.setAdjustedRiskMap(riskMap);

        // ── Step 5: Stability Scoring ─────────────────────────────────────────
        ProjectRiskSummary riskSummary = timed(ctx, "scoring",
                () -> stabilityScorer.score(riskMap, callGraph, config.toScoringConfig()));
        ctx.setRiskSummary(riskSummary);

        // ── Step 5a: Collect API Endpoints from Analysis result ───────────────
        List<ApiEndpointModel> apiEndpoints = timed(ctx, "api-endpoints",
                () -> collectApiEndpoints(analysisOrchestrator.getApiEndpointAnalyzer()));
        ctx.setApiEndpoints(apiEndpoints);

        // ── Step 5b: Collect Hotspots from Analysis result ────────────────────
        List<ArchitecturalHotspot> hotspots = timed(ctx, "hotspots",
                () -> new ArrayList<>(analysisOrchestrator.getHotspotDetector().getArchitecturalHotspots()));
        ctx.setHotspots(hotspots);

        // ── Step 6: Generate Suggestions ─────────────────────────────────────
        List<com.nullguard.suggestions.model.Suggestion> suggestions = timed(ctx, "suggestions",
                () -> suggestionEngine.generate(projectModel, riskMap, callGraph));
        ctx.setSuggestions(suggestions);

        // ── Step 7: Build Propagation Graph ──────────────────────────────────
        PropagationGraph propGraph = timed(ctx, "visualization-graph",
                () -> propagationGraphBuilder.build(projectModel, callGraph, riskMap));
        ctx.setPropagationGraph(propGraph);

        // ── Step 8: Export Visualization Artifacts ────────────────────────────
        VisualizationBundle vizBundle = timed(ctx, "visualization-export", () -> {
            String json = jsonGraphExporter.export(propGraph, riskSummary);
            String dot  = dotGraphExporter.export(propGraph);
            return new VisualizationBundle(json, dot);
        });
        ctx.setVisualizationBundle(vizBundle);

        // ── Step 9: Architecture Integrity Validation ─────────────────────────
        timed(ctx, "validation", () -> {
            architectureValidator.validateArchitectureIntegrity(ctx);
            architectureValidator.validateNoCircularDependencies(ctx);
            architectureValidator.validateDeterministicOrdering(ctx);
            return null;
        });

        // ── Step 10: Assemble Final Result ────────────────────────────────────
        ctx.getTiming().markPipelineEnd();
        return resultAssembler.assemble(ctx);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<ApiEndpointModel> collectApiEndpoints(
            com.nullguard.analysis.api.ApiEndpointAnalyzer analyzer) {
        List<ApiEndpointModel> result = new ArrayList<>();
        for (Map.Entry<String, ReachData> entry :
                analyzer.getReachTracker().getReachMap().entrySet()) {
            result.add(new ApiEndpointModel(entry.getKey()));
        }
        return result;
    }

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
