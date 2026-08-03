package com.nullguard.analysis.risk;

import com.nullguard.core.risk.RiskLevel;

import java.util.Objects;

/**
 * A method's intrinsic risk score and band.
 *
 * <p>{@code riskLevel} is the unified {@link com.nullguard.core.risk.RiskLevel}. It used to be
 * {@code com.nullguard.analysis.risk.RiskLevel}, which meant this object could not be handed to
 * the scoring or visualization modules without a conversion that was never written.
 */
public final class RiskModel {

    private final int intrinsicRiskScore;
    private final RiskLevel riskLevel;

    public RiskModel(int intrinsicRiskScore, RiskLevel riskLevel) {
        this.intrinsicRiskScore = intrinsicRiskScore;
        this.riskLevel = Objects.requireNonNull(riskLevel);
    }

    public int getIntrinsicRiskScore() { return intrinsicRiskScore; }

    public RiskLevel getRiskLevel() { return riskLevel; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RiskModel that)) return false;
        return intrinsicRiskScore == that.intrinsicRiskScore && riskLevel == that.riskLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(intrinsicRiskScore, riskLevel);
    }

    @Override
    public String toString() {
        return "RiskModel{score=" + intrinsicRiskScore + ", level=" + riskLevel + '}';
    }
}
