package com.nullguard.scoring.model;

import java.util.Objects;

public final class AdjustedRiskModel {
    private final double intrinsicRisk;
    private final double propagatedRisk;
    private final double adjustedRisk;
    private final RiskLevel riskLevel;

    public AdjustedRiskModel(double intrinsicRisk, double propagatedRisk, double adjustedRisk, RiskLevel riskLevel) {
        this.intrinsicRisk = intrinsicRisk;
        this.propagatedRisk = propagatedRisk;
        this.adjustedRisk = adjustedRisk;
        this.riskLevel = riskLevel;
    }

    public double getIntrinsicRisk() {
        return intrinsicRisk;
    }

    public double getPropagatedRisk() {
        return propagatedRisk;
    }

    public double getAdjustedRisk() {
        return adjustedRisk;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AdjustedRiskModel that = (AdjustedRiskModel) o;
        return Double.compare(that.intrinsicRisk, intrinsicRisk) == 0 &&
                Double.compare(that.propagatedRisk, propagatedRisk) == 0 &&
                Double.compare(that.adjustedRisk, adjustedRisk) == 0 &&
                riskLevel == that.riskLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(intrinsicRisk, propagatedRisk, adjustedRisk, riskLevel);
    }
}
