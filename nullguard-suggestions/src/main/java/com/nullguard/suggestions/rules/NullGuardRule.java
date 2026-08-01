package com.nullguard.suggestions.rules;

import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.model.SuggestionType;

import java.util.Optional;

public class NullGuardRule implements SuggestionRule {
    @Override
    public Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel) {
        // Typed comparison. This used to read .name().equals("NULLABLE") — a string the
        // NullState enum has never contained ({NULL, NON_NULL, UNKNOWN}), so the rule could
        // never fire. Stringly-typed enum matching is what let it compile; a direct == would
        // have been a compile error. MethodSummaryEngine sets NULL when `return null` is seen.
        if (riskModel.getAdjustedRisk() >= 60.0 && summary.getReturnNullability() == NullState.NULL) {
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
