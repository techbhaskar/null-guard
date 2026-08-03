package com.nullguard.suggestions.ranking;

import com.nullguard.suggestions.model.Suggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orders suggestions by expected value.
 *
 * <h3>Why this changed</h3>
 * The sort was already {@code comparingDouble(Suggestion::getFinalScore).reversed()}, but all
 * five rules hard-coded {@code finalScore = 0.0}. Every element tied, so the sort was a stable
 * no-op and prioritisation did not exist. The three inputs that should have produced the score —
 * {@code riskReductionEstimate}, {@code confidence} and {@code priorityWeight} — were populated
 * with per-rule constants and never combined.
 *
 * <p>The score is now computed here rather than in each rule, so rules only have to state their
 * three estimates and cannot forget to combine them:
 *
 * <pre>
 *   finalScore = riskReductionEstimate × confidence × priorityWeight
 * </pre>
 *
 * <p>Multiplicative because the three are independent factors: expected points recovered, the
 * probability the finding is real, and how much the codebase cares. A high estimate with low
 * confidence should not outrank a moderate estimate we are sure about.
 *
 * <p>Ties break on method id so output stays deterministic across runs — the pipeline asserts
 * run-to-run stability, and an unstable order would surface as a spurious diff.
 */
public class SuggestionRanker {

    /**
     * @return a new list ordered by descending computed score, ties broken by method id then
     *         suggestion type
     */
    public List<Suggestion> rank(List<Suggestion> suggestions) {
        List<Suggestion> scored = new ArrayList<>(suggestions.size());
        for (Suggestion s : suggestions) {
            scored.add(withComputedScore(s));
        }
        scored.sort(Comparator
                .comparingDouble(Suggestion::getFinalScore).reversed()
                .thenComparing(Suggestion::getMethodId)
                .thenComparing(s -> s.getSuggestionType().name()));
        return scored;
    }

    /** Expected value of acting on this suggestion. */
    public static double computeFinalScore(Suggestion s) {
        return s.getRiskReductionEstimate() * s.getConfidence() * s.getPriorityWeight();
    }

    private static Suggestion withComputedScore(Suggestion s) {
        double score = computeFinalScore(s);
        if (Double.compare(score, s.getFinalScore()) == 0) return s;
        return new Suggestion(
                s.getMethodId(),
                s.getSuggestionType(),
                s.getMessage(),
                s.getRiskReductionEstimate(),
                s.getConfidence(),
                s.getPriorityWeight(),
                score);
    }
}
