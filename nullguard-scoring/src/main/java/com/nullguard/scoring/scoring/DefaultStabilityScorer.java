package com.nullguard.scoring.scoring;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * DefaultStabilityScorer – computes the project-level stability metrics
 * from the per-method {@link AdjustedRiskModel} map.
 *
 * <h3>v1.1 FinalRisk formula</h3>
 * <pre>
 *   FinalRisk = IntrinsicRisk + PropagatedRisk + APIExposureWeight + ContractPenalty
 * </pre>
 * All four components are stored on {@link AdjustedRiskModel} and therefore
 * already summed in {@code model.getAdjustedRisk()}. The scorer uses the
 * pre-summed value when computing the stability index, but also surfaces the
 * individual component totals in the summary for transparency.
 *
 * <h3>Output metrics</h3>
 * <ul>
 *   <li>StabilityIndex  = 100 − averageAdjustedRisk</li>
 *   <li>Grade           = A/B/C/D/F</li>
 *   <li>HighRiskRatio</li>
 *   <li>BlastRadiusScore</li>
 *   <li>ContractViolationCount</li>
 * </ul>
 */
public class DefaultStabilityScorer implements StabilityScorer {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStabilityScorer.class);

    /**
     * Grade emitted when there is nothing to score. Distinct from A–F so that
     * "analysis produced no data" can never be mistaken for "the code is clean".
     * Callers should check {@link ProjectRiskSummary#isAvailable()} before rendering numbers.
     */
    public static final String GRADE_NOT_AVAILABLE = "N/A";

    @Override
    public ProjectRiskSummary score(Map<String, AdjustedRiskModel> finalModels,
                                   GlobalCallGraph callGraph,
                                   ScoringConfig config) {

        if (finalModels.isEmpty()) {
            // Do NOT return 100.0 / "A" here. An empty risk map does not mean "clean codebase";
            // in practice it means the analysis produced nothing — the parse failed, no CFGs
            // were built, or the reflective probes in FixpointRiskPropagationEngine all missed.
            // Reporting a perfect score for a total analysis failure is the single most
            // dangerous failure mode a static analyser can have, because it is indistinguishable
            // from success. Emit a sentinel the CLI and Maven plugin can render as "no data".
            LOG.warn("Stability scoring received zero risk models. This usually means parsing or "
                   + "analysis produced no results, not that the project is risk-free. "
                   + "Reporting grade '{}' instead of a score.", GRADE_NOT_AVAILABLE);
            return new ProjectRiskSummary(
                    Double.NaN, GRADE_NOT_AVAILABLE, Double.NaN, Double.NaN, Double.NaN, 0, 0, Double.NaN, 0);
        }

        int    totalMethods               = finalModels.size();
        double sumAdjustedRisk            = 0.0;
        double maxRisk                    = 0.0;
        int    highRiskMethods            = 0;
        double totalIncomingToHighRisk    = 0.0;
        double totalContractPenalty       = 0.0;
        double totalApiExposure           = 0.0;

        int highRiskThreshold = config.getHighRiskThreshold();

        for (Map.Entry<String, AdjustedRiskModel> entry : finalModels.entrySet()) {
            String            methodId = entry.getKey();
            AdjustedRiskModel model    = entry.getValue();

            // v1.1: adjustedRisk already includes apiExposureWeight + contractPenalty
            double risk = model.getAdjustedRisk();

            sumAdjustedRisk   += risk;
            totalContractPenalty += model.getContractPenalty();
            totalApiExposure     += model.getApiExposureWeight();

            if (risk > maxRisk) maxRisk = risk;

            if (risk >= highRiskThreshold) {
                highRiskMethods++;
                // Blast radius = what breaks when this method fails, i.e. who depends on it.
                // This used to sum getCallees(), which is out-degree: how many things the
                // risky method calls. That is the opposite of the metric's name, and it made
                // a leaf method with a huge fan-out look dangerous while a widely-depended-on
                // utility looked safe.
                totalIncomingToHighRisk += callGraph.getCallers(methodId).size();
            }
        }

        double averageRisk       = sumAdjustedRisk / totalMethods;
        double highRiskRatio     = (double) highRiskMethods / totalMethods;
        double blastRadiusScore  = highRiskMethods > 0
                                   ? totalIncomingToHighRisk / highRiskMethods
                                   : 0.0;

        // StabilityIndex = 100 − averageAdjustedRisk (clamped to [0, 100])
        double stabilityIndex = Math.max(0.0, Math.min(100.0, 100.0 - averageRisk));

        int totalExternalMethods = callGraph.getExternalNodes().size();
        String grade = assignGrade(stabilityIndex);

        return new ProjectRiskSummary(
                stabilityIndex,
                grade,
                averageRisk,
                maxRisk,
                highRiskRatio,
                totalMethods,
                highRiskMethods,
                blastRadiusScore,
                totalExternalMethods
        );
    }

    private static String assignGrade(double stabilityIndex) {
        if (stabilityIndex >= 90.0) return "A";
        if (stabilityIndex >= 80.0) return "B";
        if (stabilityIndex >= 70.0) return "C";
        if (stabilityIndex >= 60.0) return "D";
        return "F";
    }
}
