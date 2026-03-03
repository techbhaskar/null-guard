package com.nullguard.scoring.propagation;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;

import java.util.Map;

public interface RiskPropagationEngine {
    Map<String, AdjustedRiskModel> propagate(
            ProjectModel project,
            GlobalCallGraph callGraph,
            ScoringConfig config
    );
}
