package com.nullguard.analysis.risk;

import com.nullguard.analysis.engine.NullAnalysisModel;
import com.nullguard.core.risk.RiskLevel;

/**
 * Turns a {@link NullAnalysisModel} into an intrinsic risk score and band.
 *
 * <p>Banding is delegated to {@link RiskLevel#from(double)}. It used to be an inline
 * if-chain at 20 / 50 / 80, which disagreed with the scoring module's 40 / 60 / 80 — the same
 * number meant different things depending on which module you asked.
 */
public final class IntrinsicRiskCalculator {

    /** Points added per unguarded dereference. */
    static final int DEREFERENCE_WEIGHT = 20;
    /** Points added when the method can return null. */
    static final int NULLABLE_RETURN_WEIGHT = 10;
    /** Points added when a null value flows through the method. */
    static final int NULL_PROPAGATION_WEIGHT = 15;

    public RiskModel calculate(NullAnalysisModel model) {
        int score = 0;
        score += model.getUnguardedDereferences() * DEREFERENCE_WEIGHT;
        if (model.isNullableReturn()) score += NULLABLE_RETURN_WEIGHT;
        if (model.isPropagatesNullFromCallee()) score += NULL_PROPAGATION_WEIGHT;

        score = Math.min(100, Math.max(0, score));

        return new RiskModel(score, RiskLevel.from(score));
    }
}
