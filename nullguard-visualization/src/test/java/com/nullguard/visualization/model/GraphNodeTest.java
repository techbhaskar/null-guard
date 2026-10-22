package com.nullguard.visualization.model;

import com.nullguard.core.risk.RiskLevel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphNodeTest {
    @Test void graphKeysPreserveDifferencesInExposureAndContractPenalty() {
        var base = node(10, 5);
        var same = node(10, 5);
        var exposure = node(11, 5);
        var contract = node(10, 6);
        assertEquals(base, same);
        assertEquals(base.hashCode(), same.hashCode());
        assertEquals(base, base);
        assertNotEquals(base, null);
        assertNotEquals(base, "method");
        assertNotEquals(base, exposure);
        assertNotEquals(base, contract);
        assertEquals(3, new HashSet<>(List.of(base, same, exposure, contract)).size());
        assertTrue(base.getImpactMap().isEmpty());
    }
    private static GraphNode node(double exposure, double contract) {
        return new GraphNode("demo.Api#load()", 20, 5, exposure, contract, 40, RiskLevel.from(40), false, null);
    }
}
