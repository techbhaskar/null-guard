package com.nullguard.suggestions.rules;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.model.SuggestionType;

import java.util.Optional;

public class ExternalValidationRule implements SuggestionRule {
    
    private final GlobalCallGraph callGraph;
    
    public ExternalValidationRule(GlobalCallGraph callGraph) {
        this.callGraph = callGraph;
    }

    @Override
    public Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel) {
        if (callGraph != null && callGraph.isExternal(methodId) && riskModel.getAdjustedRisk() >= 50.0) {
            return Optional.of(new Suggestion(
                    methodId,
                    SuggestionType.VALIDATE_EXTERNAL_RETURN,
                    "Validate return from external call securely",
                    25.0,
                    0.85,
                    0.9,
                    0.0
            ));
        }
        return Optional.empty();
    }
}
