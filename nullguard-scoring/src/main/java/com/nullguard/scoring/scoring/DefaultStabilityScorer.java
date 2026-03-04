package com.nullguard.scoring.scoring;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.scoring.model.RiskLevel;

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

    @Override
    public ProjectRiskSummary score(Map<String, AdjustedRiskModel> finalModels,
                                   GlobalCallGraph callGraph,
                                   ScoringConfig config) {

        if (finalModels.isEmpty()) {
            return new ProjectRiskSummary(100.0, "A", 0.0, 0.0, 0.0, 0, 0, 0.0, 0);
        }

        int    totalMethods               = finalModels.size();
        double sumAdjustedRisk            = 0.0;
        double maxRisk                    = 0.0;
        int    highRiskMethods            = 0;
        double totalOutgoingFromHighRisk  = 0.0;
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
                totalOutgoingFromHighRisk += callGraph.getCallees(methodId).size();
            }
        }

        double averageRisk       = sumAdjustedRisk / totalMethods;
        double highRiskRatio     = (double) highRiskMethods / totalMethods;
        double blastRadiusScore  = highRiskMethods > 0
                                   ? totalOutgoingFromHighRisk / highRiskMethods
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
