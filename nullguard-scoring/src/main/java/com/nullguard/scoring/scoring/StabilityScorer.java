package com.nullguard.scoring.scoring;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;

import java.util.Map;

public interface StabilityScorer {
    ProjectRiskSummary score(
            Map<String, AdjustedRiskModel> finalModels,
            GlobalCallGraph callGraph,
            ScoringConfig config
    );
}
