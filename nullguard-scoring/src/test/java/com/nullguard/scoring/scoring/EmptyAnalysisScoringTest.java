package com.nullguard.scoring.scoring;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.scoring.model.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed analysis must never be reported as a clean project.
 *
 * <p>The scorer used to return {@code 100.0 / "A"} when the risk map was empty. An empty risk
 * map is produced by a parse failure, a CFG-building failure, or the reflective probes in
 * {@code FixpointRiskPropagationEngine} all missing — none of which mean the code is safe.
 * A total analysis failure that presents as a perfect score is the worst failure mode a
 * static analyser can have, because nothing downstream can tell it apart from success.
 */
class EmptyAnalysisScoringTest {

    private static GlobalCallGraph emptyCallGraph() {
        return new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>());
    }

    @Test
    @DisplayName("empty risk map yields the N/A sentinel, never grade A")
    void emptyAnalysisIsNotGradeA() {
        ProjectRiskSummary summary = new DefaultStabilityScorer()
                .score(new LinkedHashMap<>(), emptyCallGraph(), ScoringConfig.builder().build());

        assertEquals(DefaultStabilityScorer.GRADE_NOT_AVAILABLE, summary.getGrade());
        assertNotEquals("A", summary.getGrade());
        assertFalse(summary.isAvailable(), "a summary with no data must report itself unavailable");
        assertTrue(Double.isNaN(summary.getStabilityIndex()),
                "no data must not be rendered as a numeric score");
    }

    @Test
    @DisplayName("a genuinely clean project still scores 100 / A and reports itself available")
    void realCleanProjectStillScoresA() {
        Map<String, AdjustedRiskModel> models = new LinkedHashMap<>();
        models.put("com.acme.Foo#bar()",
                new AdjustedRiskModel(0.0, 0.0, 0.0, 0.0, 0.0, RiskLevel.LOW));

        ProjectRiskSummary summary = new DefaultStabilityScorer()
                .score(models, emptyCallGraph(), ScoringConfig.builder().build());

        assertTrue(summary.isAvailable());
        assertEquals("A", summary.getGrade());
        assertEquals(100.0, summary.getStabilityIndex(), 0.0001);
    }
}
