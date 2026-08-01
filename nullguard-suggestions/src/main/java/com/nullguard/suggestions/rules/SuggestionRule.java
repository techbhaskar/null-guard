package com.nullguard.suggestions.rules;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;

import java.util.Optional;

public interface SuggestionRule {

    /**
     * @param methodId  canonical id from {@code MethodIds}; may denote an external node
     * @param summary   the method's analysis summary, or {@code null} when the id has no
     *                  summary (external / unresolved callee). Rules that return {@code false}
     *                  from {@link #requiresSummary()} MUST tolerate a {@code null} here.
     * @param riskModel never {@code null}
     */
    Optional<Suggestion> evaluate(String methodId, MethodSummary summary, AdjustedRiskModel riskModel);

    /**
     * Whether this rule needs a {@link MethodSummary} to say anything useful.
     *
     * <p>External nodes ({@code ext#...}) appear in the risk map but have no summary, because
     * they are not methods in the analysed project. The engine used to {@code continue} on a
     * missing summary before running any rule, which made {@code ExternalValidationRule} —
     * whose entire predicate is {@code callGraph.isExternal(methodId)} — permanently
     * unreachable. Graph-shape rules override this to {@code false} so they still get a turn.
     *
     * @return {@code true} by default
     */
    default boolean requiresSummary() {
        return true;
    }
}
