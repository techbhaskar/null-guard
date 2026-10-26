package com.nullguard.scoring.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRiskSummaryTest {
    @Test
    void exposesValuesAndImplementsValueEquality() {
        ProjectRiskSummary summary = new ProjectRiskSummary(82, "B", 18, 76, 0.2, 20, 4, 0.5, 3);
        ProjectRiskSummary equal = new ProjectRiskSummary(82, "B", 18, 76, 0.2, 20, 4, 0.5, 3);
        ProjectRiskSummary different = new ProjectRiskSummary(82, "C", 18, 76, 0.2, 20, 4, 0.5, 3);

        assertTrue(summary.isAvailable());
        assertEquals(82, summary.getStabilityIndex());
        assertEquals("B", summary.getGrade());
        assertEquals(18, summary.getAverageRisk());
        assertEquals(76, summary.getMaxRisk());
        assertEquals(0.2, summary.getHighRiskRatio());
        assertEquals(20, summary.getTotalMethods());
        assertEquals(4, summary.getHighRiskMethods());
        assertEquals(0.5, summary.getBlastRadiusScore());
        assertEquals(3, summary.getTotalExternalMethods());
        assertEquals(summary, summary);
        assertEquals(summary, equal);
        assertEquals(summary.hashCode(), equal.hashCode());
        assertNotEquals(summary, different);
        assertNotEquals(summary, null);
        assertNotEquals(summary, "not a summary");
        assertNotEquals(summary, new ProjectRiskSummary(83, "B", 18, 76, 0.2, 20, 4, 0.5, 3));
        assertNotEquals(summary, new ProjectRiskSummary(82, "B", 19, 76, 0.2, 20, 4, 0.5, 3));
        assertNotEquals(summary, new ProjectRiskSummary(82, "B", 18, 77, 0.2, 20, 4, 0.5, 3));
        assertNotEquals(summary, new ProjectRiskSummary(82, "B", 18, 76, 0.3, 20, 4, 0.5, 3));
        assertNotEquals(summary, new ProjectRiskSummary(82, "B", 18, 76, 0.2, 21, 4, 0.5, 3));
        assertNotEquals(summary, new ProjectRiskSummary(82, "B", 18, 76, 0.2, 20, 5, 0.5, 3));
        assertNotEquals(summary, new ProjectRiskSummary(82, "B", 18, 76, 0.2, 20, 4, 0.6, 3));
        assertNotEquals(summary, new ProjectRiskSummary(82, "B", 18, 76, 0.2, 20, 4, 0.5, 4));
    }

    @Test
    void marksEmptyAnalysisUnavailable() {
        ProjectRiskSummary summary = new ProjectRiskSummary(Double.NaN, "N/A", Double.NaN,
                Double.NaN, Double.NaN, 0, 0, Double.NaN, 0);

        assertFalse(summary.isAvailable());
        assertNotEquals(summary, new ProjectRiskSummary(0, "N/A", Double.NaN,
                Double.NaN, Double.NaN, 0, 0, Double.NaN, 0));
    }
}
