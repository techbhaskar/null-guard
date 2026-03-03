package com.nullguard.scoring.scoring;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;

import java.util.Map;

public class DefaultStabilityScorer implements StabilityScorer {

    @Override
    public ProjectRiskSummary score(Map<String, AdjustedRiskModel> finalModels, GlobalCallGraph callGraph, ScoringConfig config) {
        if (finalModels.isEmpty()) {
            return new ProjectRiskSummary(100.0, "A", 0.0, 0.0, 0.0, 0, 0, 0.0, 0);
        }

        int totalMethods = finalModels.size();
        double sumRisk = 0.0;
        double maxRisk = 0.0;
        int highRiskMethods = 0;
        double totalOutgoingFromHighRisk = 0.0;
        
        int highRiskThreshold = config.getHighRiskThreshold();

        for (Map.Entry<String, AdjustedRiskModel> entry : finalModels.entrySet()) {
            String methodId = entry.getKey();
            AdjustedRiskModel model = entry.getValue();
            double risk = model.getAdjustedRisk();
            
            sumRisk += risk;
            if (risk > maxRisk) {
                maxRisk = risk;
            }
            if (risk >= highRiskThreshold) {
                highRiskMethods++;
                totalOutgoingFromHighRisk += callGraph.getCallees(methodId).size();
            }
        }

        double averageRisk = sumRisk / totalMethods;
        double highRiskRatio = (double) highRiskMethods / totalMethods;
        double blastRadiusScore = highRiskMethods > 0 ? totalOutgoingFromHighRisk / highRiskMethods : 0.0;
        double stabilityIndex = 100.0 - averageRisk;

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

    private String assignGrade(double stabilityIndex) {
        if (stabilityIndex >= 90.0) return "A";
        if (stabilityIndex >= 80.0) return "B";
        if (stabilityIndex >= 70.0) return "C";
        if (stabilityIndex >= 60.0) return "D";
        return "F";
    }
}
