package com.nullguard.suggestions.rules;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.model.SuggestionType;

import java.util.Optional;

public class RiskIsolationRule implements SuggestionRule {

    private final GlobalCallGraph callGraph;

    public RiskIsolationRule(GlobalCallGraph callGraph) {
        this.callGraph = callGraph;
    }

    @Override
    public Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel) {
        if (callGraph != null && callGraph.getCallees(methodId).size() > 5 && riskModel.getAdjustedRisk() >= 60.0) {
            return Optional.of(new Suggestion(
                    methodId,
                    SuggestionType.BREAK_RISK_CHAIN,
                    "Isolate high-risk module components by breaking dependencies",
                    40.0,
                    0.6,
                    0.7,
                    0.0
            ));
        }
        return Optional.empty();
    }
}
