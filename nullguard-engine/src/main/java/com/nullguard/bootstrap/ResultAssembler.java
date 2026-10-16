package com.nullguard.bootstrap;

import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.suggestions.model.Suggestion;

import java.util.List;
import java.util.Objects;

/**
 * ResultAssembler – collects all intermediate artifacts from {@link PipelineContext}
 * and assembles the final, immutable {@link FinalAnalysisResult}.
 *
 * This class performs NO analysis logic. It only reads from the context
 * and packages the result for consumers (CLI, Maven Plugin, etc.).
 */
public final class ResultAssembler {

    /**
     * Assemble a {@link FinalAnalysisResult} from the fully-populated context.
     *
     * @param ctx fully-populated pipeline context (all steps must have run)
     * @return immutable FinalAnalysisResult
     */
    public FinalAnalysisResult assemble(PipelineContext ctx) {
        Objects.requireNonNull(ctx, "PipelineContext must not be null");

        ProjectRiskSummary        riskSummary    = ctx.getRiskSummary();
        List<ApiEndpointModel>    apiEndpoints   = resolveApiEndpoints(ctx);
        List<ArchitecturalHotspot> hotspots      = ctx.getHotspots();
        List<Suggestion>          suggestions    = ctx.getSuggestions();
        VisualizationBundle       visualizations = ctx.getVisualizationBundle();
        TimingMetrics             timing         = ctx.getTiming();

        return new FinalAnalysisResult(
                riskSummary,
                apiEndpoints,
                hotspots,
                suggestions,
                visualizations,
                timing,
                ctx.getCycleWarnings(),
                ctx.getRiskReasonMap()
        );

    }

    /**
     * Converts the reachability tracker's reach map into a list of ApiEndpointModels.
     * Uses deterministic iteration order (LinkedHashMap preserved from ApiEndpointAnalyzer).
     */
    private List<ApiEndpointModel> resolveApiEndpoints(PipelineContext ctx) {
        // ApiEndpoints are already stored in context by the pipeline step
        return ctx.getApiEndpoints();
    }
}
