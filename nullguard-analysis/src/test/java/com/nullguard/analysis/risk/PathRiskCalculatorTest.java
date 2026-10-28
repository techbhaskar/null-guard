package com.nullguard.analysis.risk;

import com.nullguard.analysis.config.AnalysisConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathRiskCalculatorTest {
    @Test
    void weightsPathDepthByDistanceAndConfiguredDecay() {
        AnalysisConfig config = new AnalysisConfig(10, 2.0, 0.3, 70, 5);
        PathRiskCalculator calculator = new PathRiskCalculator(config);

        assertEquals(2.5, calculator.computePathRisk(5, 1.0));
        assertEquals(1.0, calculator.computePathRisk(5, 2.0));
        assertEquals(0.0, calculator.computePathRisk(0, 1.0));
        assertEquals(10, config.getPropagationDepthLimit());
        assertEquals(2.0, config.getPropagationDecayExponent());
        assertEquals(0.3, config.getApiRiskWeights());
        assertEquals(70, config.getHotspotRiskThreshold());
        assertEquals(5, config.getHotspotReachThreshold());
    }
}
