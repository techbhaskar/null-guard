package com.nullguard.analysis.risk;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;

/**
 * @deprecated SAFE TO DELETE — no longer referenced by the pipeline.
 *
 * <p>{@code propagate(ProjectModel)} was an <strong>empty method body</strong> with the comment
 * "Aggregates risk computation", and both of its fields ({@code config},
 * {@code pathRiskCalculator}) were never read. It was nonetheless called unconditionally from
 * {@code AnalysisOrchestrator.analyze}, which made the pipeline read as though intra-module risk
 * propagation happened here.
 *
 * <p>Risk propagation actually lives in
 * {@code com.nullguard.scoring.propagation.FixpointRiskPropagationEngine}, which is where the
 * fixpoint iteration, decay and clamping are implemented. There is no second propagation step,
 * so this class is removed from the orchestrator rather than given an implementation that would
 * duplicate it.
 *
 * <p>Left in place only because files could not be deleted from the environment this change was
 * made in. Please {@code git rm} it, along with {@code PathRiskCalculator} if nothing else
 * references it.
 */
@Deprecated(forRemoval = true)
public class RiskEngine {

    private final AnalysisConfig config;

    public RiskEngine(AnalysisConfig config) {
        this.config = config;
    }

    /**
     * @deprecated no-op. Use
     *             {@code com.nullguard.scoring.propagation.FixpointRiskPropagationEngine}.
     */
    @Deprecated(forRemoval = true)
    public void propagate(ProjectModel project) {
        throw new UnsupportedOperationException(
                "RiskEngine.propagate was always an empty method. Risk propagation is performed by "
                        + "FixpointRiskPropagationEngine in nullguard-scoring. Remove this call.");
    }
}
