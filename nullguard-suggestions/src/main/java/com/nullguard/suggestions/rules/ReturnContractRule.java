package com.nullguard.suggestions.rules;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.model.SuggestionType;

import java.util.Optional;

public class ReturnContractRule implements SuggestionRule {
    @Override
    public Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel) {
        if (riskModel.getIntrinsicRisk() >= 40.0 && summary.getReturnNullability().name().equals("UNKNOWN")) {
            return Optional.of(new Suggestion(
                    methodId,
                    SuggestionType.STRENGTHEN_CONTRACT,
                    "Strengthen return contract by avoiding UNKNOWN nullability",
                    10.0,
                    0.9,
                    0.6,
                    0.0
            ));
        }
        return Optional.empty();
    }
}
