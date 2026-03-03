package com.nullguard.bootstrap;

import com.nullguard.analysis.orchestrator.AnalysisOrchestrator;
import com.nullguard.callgraph.builder.BasicCallGraphBuilder;
import com.nullguard.core.parser.JavaParserAstParser;
import com.nullguard.scoring.propagation.FixpointRiskPropagationEngine;
import com.nullguard.scoring.scoring.DefaultStabilityScorer;
import com.nullguard.suggestions.engine.DefaultSuggestionEngine;
import com.nullguard.visualization.export.DotGraphExporter;
import com.nullguard.visualization.export.JsonGraphExporter;
import com.nullguard.visualization.graph.DefaultPropagationGraphBuilder;

import java.nio.file.Path;
import java.util.Objects;

/**
 * EngineBootstrap – the single entry point for constructing and executing
 * the NullGuard analysis engine.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li>Accept a fully-built {@link NullGuardConfig} (configuration loaded externally)</li>
 *   <li>Instantiate all modules via <em>constructor injection only</em></li>
 *   <li>Wire dependencies in the correct direction:
 *       {@code core → callgraph → analysis → scoring → suggestions → visualization}</li>
 *   <li>Create the {@link AnalysisPipeline} and inject all dependencies into it</li>
 *   <li>Expose a single {@link #run(Path)} method for callers</li>
 * </ol>
 *
 * <p><strong>No Spring. No CDI. No static wiring.</strong>
 * Manual constructor injection throughout.
 *
 * <p>Thread-safety: instances are <em>not</em> thread-safe. Create one per analysis invocation
 * if concurrent analyses are needed.
 */
public final class EngineBootstrap {

    private final NullGuardConfig   config;
    private final AnalysisPipeline  pipeline;

    /**
     * Constructs and wires the complete NullGuard engine from a given configuration.
     *
     * @param config The single unified configuration object for this analysis run.
     */
    public EngineBootstrap(NullGuardConfig config) {
        this.config = Objects.requireNonNull(config, "NullGuardConfig must not be null");
        this.pipeline = buildPipeline(config);
    }

    /**
     * Execute the full NullGuard analysis pipeline for the given project source root.
     *
     * @param projectRoot path to the Java source root to analyze
     * @return a fully-assembled {@link FinalAnalysisResult}
     */
    public FinalAnalysisResult run(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        return pipeline.execute(projectRoot, config);
    }

    // ── Private Wiring ───────────────────────────────────────────────────────

    /**
     * Instantiates all modules and assembles the pipeline.
     * Dependency direction: core → callgraph → analysis → scoring → suggestions → visualization.
     * No reverse dependency is permitted.
     */
    private static AnalysisPipeline buildPipeline(NullGuardConfig config) {

        // ── Layer 1: Core ────────────────────────────────────────────────────
        JavaParserAstParser coreParser = new JavaParserAstParser();

        // ── Layer 2: Call Graph (depends on core IR from analysis) ───────────
        BasicCallGraphBuilder callGraphBuilder = new BasicCallGraphBuilder();

        // ── Layer 3: Analysis (depends on core) ──────────────────────────────
        AnalysisOrchestrator analysisOrchestrator =
                new AnalysisOrchestrator(config.toAnalysisConfig());

        // ── Layer 4: Scoring (depends on core + callgraph) ───────────────────
        FixpointRiskPropagationEngine riskPropagationEngine =
                new FixpointRiskPropagationEngine();

        DefaultStabilityScorer stabilityScorer =
                new DefaultStabilityScorer();

        // ── Layer 5: Suggestions (depends on core + analysis + callgraph + scoring) ──
        DefaultSuggestionEngine suggestionEngine =
                new DefaultSuggestionEngine();

        // ── Layer 6: Visualization (depends on core + callgraph + scoring) ───
        DefaultPropagationGraphBuilder propagationGraphBuilder =
                new DefaultPropagationGraphBuilder();

        JsonGraphExporter jsonGraphExporter = new JsonGraphExporter();
        DotGraphExporter  dotGraphExporter  = new DotGraphExporter();

        // ── Result Assembler + Validator ──────────────────────────────────────
        ResultAssembler      resultAssembler      = new ResultAssembler();
        ArchitectureValidator architectureValidator = new ArchitectureValidator();

        // ── Assemble Pipeline ─────────────────────────────────────────────────
        return new AnalysisPipeline(
                coreParser,
                callGraphBuilder,
                analysisOrchestrator,
                riskPropagationEngine,
                stabilityScorer,
                suggestionEngine,
                propagationGraphBuilder,
                jsonGraphExporter,
                dotGraphExporter,
                resultAssembler,
                architectureValidator
        );
    }
}
