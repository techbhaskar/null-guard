package com.nullguard.core.risk;

/**
 * The single risk classification used across every NullGuard module.
 *
 * <h3>Why this is in core</h3>
 * There used to be two enums with the same simple name, the same four constants, different
 * packages, different band boundaries, and no conversion between them:
 *
 * <ul>
 *   <li>{@code com.nullguard.analysis.risk.RiskLevel} — bare constants, with the bands
 *       hard-coded separately in {@code IntrinsicRiskCalculator} as 20 / 50 / 80.</li>
 *   <li>{@code com.nullguard.scoring.model.RiskLevel} — carried its own ranges of
 *       0-39 / 40-59 / 60-79 / 80-100 plus a {@code from(double)}.</li>
 * </ul>
 *
 * <p>Both were consumed in the same run, so a score of 30 was MEDIUM in analysis and LOW in
 * scoring, and 55 was HIGH in analysis and MEDIUM in scoring. Because
 * {@code nullguard-visualization} imported the scoring one while {@code RiskModel} carried the
 * analysis one, there was no way to move a risk value downstream without a hand-written mapping
 * that never existed — and {@code ScoringTest} was forced to fully-qualify one of them just to
 * compile, which is the design smell surfacing as a compile constraint.
 *
 * <p>Placing it in {@code nullguard-core} means every module can name it without depending on a
 * sibling, and there is exactly one definition of where each band starts.
 *
 * <h3>Bands</h3>
 * The scoring module's boundaries were adopted, because they were the ones already driving
 * user-visible output ({@code AdjustedRiskModel}, the graph colouring and the high-risk
 * threshold). {@code IntrinsicRiskCalculator} previously banded at 20 / 50 / 80 and now uses
 * these instead, so intrinsic levels shift: a score of 30 is now LOW rather than MEDIUM, and 55
 * is now MEDIUM rather than HIGH. That is the point — the two scales were never reconcilable.
 *
 * <p>The boundaries live only in the constructor arguments. {@link #from(double)} derives its
 * decisions from {@link #getMax()} rather than repeating the literals, which the previous
 * scoring enum did not do (it declared the ranges and then re-hardcoded them in {@code from}).
 */
public enum RiskLevel {

    LOW(0, 39),
    MEDIUM(40, 59),
    HIGH(60, 79),
    CRITICAL(80, 100);

    private final int min;
    private final int max;

    RiskLevel(int min, int max) {
        this.min = min;
        this.max = max;
    }

    /** Inclusive lower bound of this band. */
    public int getMin() {
        return min;
    }

    /** Inclusive upper bound of this band. */
    public int getMax() {
        return max;
    }

    /**
     * Classifies a risk score.
     *
     * <p>Derived from the band data rather than repeated literals, so the boundaries cannot
     * drift away from {@link #getMin()} / {@link #getMax()}.
     *
     * @param risk score, normally within [0, 100]; values below 0 clamp to {@link #LOW} and
     *             above 100 to {@link #CRITICAL}
     */
    public static RiskLevel from(double risk) {
        for (RiskLevel level : values()) {
            if (risk <= level.max) return level;
        }
        return CRITICAL;
    }
}
