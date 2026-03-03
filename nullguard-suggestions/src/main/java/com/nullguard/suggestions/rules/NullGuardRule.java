package com.nullguard.suggestions.rules;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.model.SuggestionType;

import java.util.Optional;

public class NullGuardRule implements SuggestionRule {
    @Override
    public Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel) {
        if (riskModel.getAdjustedRisk() >= 60.0 && summary.getReturnNullability().name().equals("NULLABLE")) {
            return Optional.of(new Suggestion(
                    methodId,
                    SuggestionType.ADD_NULL_GUARD,
                    "Add null check for return value to prevent propagation",
                    15.0, // riskReductionEstimate
                    0.8,  // confidence
                    0.7,   // priorityWeight
                    0.0
            ));
        }
        return Optional.empty();
    }
}
