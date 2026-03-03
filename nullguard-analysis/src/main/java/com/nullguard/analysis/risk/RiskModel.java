package com.nullguard.analysis.risk;
import java.util.Objects;
public final class RiskModel {
    private final int intrinsicRiskScore;
    private final RiskLevel riskLevel;
    public RiskModel(int intrinsicRiskScore, RiskLevel riskLevel) {
        this.intrinsicRiskScore = intrinsicRiskScore;
        this.riskLevel = Objects.requireNonNull(riskLevel);
    }
    public int getIntrinsicRiskScore() { return intrinsicRiskScore; }
    public RiskLevel getRiskLevel() { return riskLevel; }
}
