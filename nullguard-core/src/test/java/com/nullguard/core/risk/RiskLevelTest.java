package com.nullguard.core.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RiskLevelTest {

    @Test
    void declaresExpectedBandBoundaries() {
        assertAll(
                () -> assertEquals(0, RiskLevel.LOW.getMin()),
                () -> assertEquals(39, RiskLevel.LOW.getMax()),
                () -> assertEquals(40, RiskLevel.MEDIUM.getMin()),
                () -> assertEquals(59, RiskLevel.MEDIUM.getMax()),
                () -> assertEquals(60, RiskLevel.HIGH.getMin()),
                () -> assertEquals(79, RiskLevel.HIGH.getMax()),
                () -> assertEquals(80, RiskLevel.CRITICAL.getMin()),
                () -> assertEquals(100, RiskLevel.CRITICAL.getMax())
        );
    }

    @Test
    void classifiesRiskScoresAcrossTheBandBoundaries() {
        assertAll(
                () -> assertEquals(RiskLevel.LOW, RiskLevel.from(-1.0)),
                () -> assertEquals(RiskLevel.LOW, RiskLevel.from(0.0)),
                () -> assertEquals(RiskLevel.LOW, RiskLevel.from(39.0)),
                () -> assertEquals(RiskLevel.MEDIUM, RiskLevel.from(40.0)),
                () -> assertEquals(RiskLevel.MEDIUM, RiskLevel.from(59.0)),
                () -> assertEquals(RiskLevel.HIGH, RiskLevel.from(60.0)),
                () -> assertEquals(RiskLevel.HIGH, RiskLevel.from(79.0)),
                () -> assertEquals(RiskLevel.CRITICAL, RiskLevel.from(80.0)),
                () -> assertEquals(RiskLevel.CRITICAL, RiskLevel.from(100.0)),
                () -> assertEquals(RiskLevel.CRITICAL, RiskLevel.from(101.0))
        );
    }
}
