package com.nullguard.visualization.model;

import com.nullguard.scoring.model.RiskLevel;
import java.util.Objects;

public final class GraphNode {
    private final String methodId;
    private final double intrinsicRisk;
    private final double propagatedRisk;
    private final double adjustedRisk;
    private final RiskLevel riskLevel;
    private final boolean external;

    public GraphNode(String methodId, double intrinsicRisk, double propagatedRisk, double adjustedRisk, RiskLevel riskLevel, boolean external) {
        this.methodId = Objects.requireNonNull(methodId, "methodId cannot be null");
        this.intrinsicRisk = intrinsicRisk;
        this.propagatedRisk = propagatedRisk;
        this.adjustedRisk = adjustedRisk;
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel cannot be null");
        this.external = external;
    }

    public String getMethodId() {
        return methodId;
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

    public boolean isExternal() {
        return external;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GraphNode graphNode = (GraphNode) o;
        return Double.compare(graphNode.intrinsicRisk, intrinsicRisk) == 0 &&
                Double.compare(graphNode.propagatedRisk, propagatedRisk) == 0 &&
                Double.compare(graphNode.adjustedRisk, adjustedRisk) == 0 &&
                external == graphNode.external &&
                Objects.equals(methodId, graphNode.methodId) &&
                riskLevel == graphNode.riskLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodId, intrinsicRisk, propagatedRisk, adjustedRisk, riskLevel, external);
    }
}
