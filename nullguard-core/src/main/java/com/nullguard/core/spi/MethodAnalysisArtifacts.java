package com.nullguard.core.spi;

/**
 * The typed contract between analysis passes and their downstream consumers.
 *
 * <h3>What this replaces</h3>
 * {@code MethodModel} carried four {@code Object}-typed slots — {@code methodSummary},
 * {@code contractModel}, {@code adjustedRiskModel} and {@code reachData} — written by one module
 * and read by another <em>through reflection</em>:
 *
 * <pre>
 *   java.lang.reflect.Method mScore = obj.getClass().getMethod("getIntrinsicRiskScore");
 *   risk = ((Number) mScore.invoke(obj)).doubleValue();
 *   ...
 *   } catch (Exception e2) {
 *       // Both probes failed - leave risk at 0.0
 *   }
 * </pre>
 *
 * <p>Every probe was wrapped in a catch that fell back to {@code 0.0}. The consequence was that
 * renaming {@code MethodSummary.getIntrinsicRiskScore()} would silently zero every risk score in
 * the product — no compile error, no test failure, and (before the scorer was fixed) a reported
 * grade of A. The stated justification, that reflection "avoids a compile-time dependency on
 * nullguard-analysis", was not true: {@code nullguard-scoring} already declares that dependency
 * in its POM and imports {@code ApiEndpointModel} directly.
 *
 * <p>These interfaces live in {@code nullguard-core} so that a producing module can implement
 * them and a consuming module can call them without either depending on the other. Each exposes
 * only the members that actually crossed a module boundary — they are read views, not the full
 * models.
 */
public final class MethodAnalysisArtifacts {

    private MethodAnalysisArtifacts() {
        // namespace only
    }

    /**
     * A method's own null-risk score, independent of its callees.
     *
     * <p>Implemented by {@code com.nullguard.analysis.summary.MethodSummary}; read by the
     * scoring module's risk propagation engine.
     */
    public interface IntrinsicRiskSource {
        /** @return intrinsic risk on a 0-100 scale */
        int getIntrinsicRiskScore();
    }

    /**
     * The penalty accrued from boundary-contract violations.
     *
     * <p>Implemented by {@code com.nullguard.analysis.contract.ContractModel}; read by the
     * scoring module when composing adjusted risk.
     */
    public interface ContractPenaltySource {
        /** @return penalty points to add to adjusted risk */
        int getContractPenalty();

        boolean isReturnContractViolation();

        boolean isParameterContractViolation();
    }

    /**
     * The composed per-method risk breakdown produced by risk propagation.
     *
     * <p>Implemented by {@code com.nullguard.scoring.model.AdjustedRiskModel}; read by the
     * analysis module's hotspot detector, which is why this cannot simply be a scoring type.
     */
    public interface AdjustedRiskSource {
        double getIntrinsicRisk();

        double getPropagatedRisk();

        double getApiExposureWeight();

        double getContractPenalty();

        double getAdjustedRisk();
    }

    /**
     * How many API entry points can reach a method.
     *
     * <p>Implemented by {@code com.nullguard.analysis.model.ReachData}; read by the hotspot
     * detector. Note this was previously reflected on despite {@code HotspotDetector} already
     * importing {@code ReachData} on line 10 and never otherwise referencing it.
     */
    public interface ReachCountSource {
        /** @return number of distinct API entry points that reach this method */
        int getCount();
    }
}
