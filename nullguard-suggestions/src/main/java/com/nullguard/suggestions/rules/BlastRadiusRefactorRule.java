package com.nullguard.suggestions.rules;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.model.SuggestionType;

import java.util.Optional;

public class BlastRadiusRefactorRule implements SuggestionRule {
    @Override
    public Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel) {
        if (riskModel.getPropagatedRisk() >= 40.0) {
            return Optional.of(new Suggestion(
                    methodId,
                    SuggestionType.REFACTOR_HIGH_BLAST_RADIUS,
                    "Refactor to reduce blast radius and contain propagation",
                    20.0,
                    0.7,
                    0.8,
                    0.0
            ));
        }
        return Optional.empty();
    }
}
