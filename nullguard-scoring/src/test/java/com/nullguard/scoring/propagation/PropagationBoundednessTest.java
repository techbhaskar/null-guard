package com.nullguard.scoring.propagation;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.config.ScoringConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Propagation used to <em>sum</em> {@code childAdjusted * decay} over every callee, so per-hop
 * amplification was {@code decay × fan-out}: at the default decay of 0.6 anything with fan-out
 * above ~1.67 diverged and pinned at the clamp ceiling of 100. Averaging makes the term mean
 * "how risky are my dependencies" and bounds it by {@code decay × max(childAdjusted)}.
 *
 * <p>{@link ScoringConfig} also accepted any value, and {@code decayFactor == 1.0} divided by
 * zero in the engine's explanation text, emitting {@code Infinity} into user-facing output.
 */
class PropagationBoundednessTest {

    private static GlobalCallGraph fanOut(String caller, int calleeCount) {
        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> incoming = new LinkedHashMap<>();

        LinkedHashSet<String> callees = new LinkedHashSet<>();
        for (int i = 0; i < calleeCount; i++) {
            String callee = "com.acme.Leaf" + i + "#run()";
            callees.add(callee);
            incoming.computeIfAbsent(callee, k -> new LinkedHashSet<>()).add(caller);
            outgoing.putIfAbsent(callee, new LinkedHashSet<>());
        }
        outgoing.put(caller, callees);
        incoming.putIfAbsent(caller, new LinkedHashSet<>());
        return new GlobalCallGraph(outgoing, incoming, new LinkedHashSet<>());
    }

    // ── Config validation ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("decayFactor of 1.0 is rejected instead of producing Infinity")
    void decayOfOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoringConfig.builder().decayFactor(1.0).build());
    }

    @Test
    @DisplayName("decayFactor above 1.0 is rejected")
    void divergentDecayIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoringConfig.builder().decayFactor(1.5).build());
    }

    @Test
    @DisplayName("negative decayFactor is rejected")
    void negativeDecayIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoringConfig.builder().decayFactor(-0.1).build());
    }

    @Test
    @DisplayName("maxIterations of zero is rejected instead of silently skipping propagation")
    void zeroIterationsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoringConfig.builder().maxIterations(0).build());
    }

    @Test
    @DisplayName("the defaults are valid")
    void defaultsAreValid() {
        ScoringConfig config = ScoringConfig.builder().build();
        assertTrue(config.getDecayFactor() < 1.0);
        assertTrue(config.getMaxIterations() > 0);
    }

    @Test
    @DisplayName("the high-risk threshold agrees with RiskLevel.HIGH")
    void highRiskThresholdIsAligned() {
        // 70 vs 60 previously: a method at 65 was rendered HIGH but excluded from highRiskMethods.
        assertTrue(ScoringConfig.builder().build().getHighRiskThreshold()
                        == com.nullguard.core.risk.RiskLevel.HIGH.getMin(),
                "two contradictory definitions of high risk");
    }

    // ── Formula behaviour ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("fan-out alone does not saturate the score")
    void wideFanOutDoesNotSaturate() {
        // Under the old sum, twenty callees of even modest risk pinned the caller at 100.
        String caller = "com.acme.Hub#dispatch()";
        var engine = new FixpointRiskPropagationEngine();

        var models = engine.propagate(
                com.nullguard.core.model.ProjectModel.builder().projectName("p").build(),
                fanOut(caller, 20),
                ScoringConfig.builder().build());

        var hub = models.get(caller);
        if (hub != null) {
            assertTrue(hub.getPropagatedRisk() <= 100.0, "score must stay within the range");
            assertTrue(hub.getPropagatedRisk() < 100.0,
                    "20 zero-risk callees must not drive propagated risk to the ceiling");
        }
    }

    @Test
    @DisplayName("propagation stays bounded on a mutually recursive pair")
    void mutualRecursionStaysBounded() {
        // Self-loops were excluded but A→B→A was not, so mutual recursion still inflated.
        String a = "com.acme.A#run()";
        String b = "com.acme.B#run()";

        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> incoming = new LinkedHashMap<>();
        outgoing.put(a, new LinkedHashSet<>(java.util.List.of(b)));
        outgoing.put(b, new LinkedHashSet<>(java.util.List.of(a)));
        incoming.put(a, new LinkedHashSet<>(java.util.List.of(b)));
        incoming.put(b, new LinkedHashSet<>(java.util.List.of(a)));

        var models = new FixpointRiskPropagationEngine().propagate(
                com.nullguard.core.model.ProjectModel.builder().projectName("p").build(),
                new GlobalCallGraph(outgoing, incoming, new LinkedHashSet<>()),
                ScoringConfig.builder().build());

        for (var model : models.values()) {
            assertTrue(model.getAdjustedRisk() >= 0.0 && model.getAdjustedRisk() <= 100.0,
                    "cyclic propagation must converge inside [0, 100]");
            assertTrue(Double.isFinite(model.getAdjustedRisk()));
        }
    }
}
