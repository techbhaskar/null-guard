package com.nullguard.suggestions.rules;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;

import java.util.Optional;

public interface SuggestionRule {
    Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel);
}
