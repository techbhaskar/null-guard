package com.nullguard.scoring.propagation;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.risk.RiskLevel;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class RiskPropagationEngineTest {

    @Test
    void defaultOverloadDelegatesToThePrimaryPropagateMethod() {
        var expected = Map.of(
                "methodA",
                new AdjustedRiskModel(10.0, 5.0, 2.0, 1.0, 18.0, RiskLevel.LOW)
        );

        RiskPropagationEngine engine = new RiskPropagationEngine() {
            @Override
            public Map<String, AdjustedRiskModel> propagate(ProjectModel project, GlobalCallGraph callGraph, ScoringConfig config) {
                return expected;
            }
        };

        var actual = engine.propagate(null, null, ScoringConfig.builder().build(), List.of());

        assertSame(expected, actual);
    }
}
