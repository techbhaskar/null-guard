package com.nullguard.scoring.model;

import java.util.Objects;

/**
 * AdjustedRiskModel – the per-method risk breakdown produced by
 * {@link com.nullguard.scoring.propagation.FixpointRiskPropagationEngine}.
 *
 * <p>v1.1 formula:
 * <pre>
 *   FinalRisk = IntrinsicRisk + PropagatedRisk + APIExposureWeight + ContractPenalty
 * </pre>
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code intrinsicRisk}       – own null-risk score (0–100)</li>
 *   <li>{@code propagatedRisk}      – risk received from callees × decay</li>
 *   <li>{@code apiExposureWeight}   – extra penalty for methods reachable from many APIs
 *                                     (BaseRisk × log(N+1), capped)</li>
 *   <li>{@code contractPenalty}     – penalty from contract violations (10 pts per return
 *                                     violation, 5 pts per parameter violation)</li>
 *   <li>{@code adjustedRisk}        – clamped sum of all four components</li>
 * </ul>
 */
public final class AdjustedRiskModel {

    private final double              intrinsicRisk;
    private final double              propagatedRisk;
    private final double              apiExposureWeight;
    private final double              contractPenalty;
    private final double              adjustedRisk;
    private final RiskLevel           riskLevel;
    private final java.util.List<com.nullguard.scoring.model.ImpactChain> impactMap;

    /** Full v1.1 constructor. */
    public AdjustedRiskModel(double intrinsicRisk,
                             double propagatedRisk,
                             double apiExposureWeight,
                             double contractPenalty,
                             double adjustedRisk,
                             RiskLevel riskLevel,
                             java.util.List<com.nullguard.scoring.model.ImpactChain> impactMap) {
        this.intrinsicRisk      = intrinsicRisk;
        this.propagatedRisk     = propagatedRisk;
        this.apiExposureWeight  = apiExposureWeight;
        this.contractPenalty    = contractPenalty;
        this.adjustedRisk       = adjustedRisk;
        this.riskLevel          = riskLevel;
        this.impactMap          = impactMap != null ? java.util.List.copyOf(impactMap) : java.util.List.of();
    }

    public AdjustedRiskModel(double intrinsicRisk,
                             double propagatedRisk,
                             double apiExposureWeight,
                             double contractPenalty,
                             double adjustedRisk,
                             RiskLevel riskLevel) {
        this(intrinsicRisk, propagatedRisk, apiExposureWeight, contractPenalty, adjustedRisk, riskLevel, java.util.List.of());
    }

    /** Backward-compat constructor (no API exposure or contract penalty). */
    public AdjustedRiskModel(double intrinsicRisk,
                             double propagatedRisk,
                             double adjustedRisk,
                             RiskLevel riskLevel) {
        this(intrinsicRisk, propagatedRisk, 0.0, 0.0, adjustedRisk, riskLevel);
    }

    public double    getIntrinsicRisk()      { return intrinsicRisk; }
    public double    getPropagatedRisk()     { return propagatedRisk; }
    public double    getApiExposureWeight()  { return apiExposureWeight; }
    public double    getContractPenalty()    { return contractPenalty; }
    public double    getAdjustedRisk()       { return adjustedRisk; }
    public RiskLevel getRiskLevel()          { return riskLevel; }
    public java.util.List<com.nullguard.scoring.model.ImpactChain> getImpactMap() { return impactMap; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AdjustedRiskModel that = (AdjustedRiskModel) o;
        return Double.compare(that.intrinsicRisk,     intrinsicRisk)     == 0
            && Double.compare(that.propagatedRisk,    propagatedRisk)    == 0
            && Double.compare(that.apiExposureWeight, apiExposureWeight) == 0
            && Double.compare(that.contractPenalty,   contractPenalty)   == 0
            && Double.compare(that.adjustedRisk,      adjustedRisk)      == 0
            && riskLevel == that.riskLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(intrinsicRisk, propagatedRisk, apiExposureWeight, contractPenalty, adjustedRisk, riskLevel);
    }
}
