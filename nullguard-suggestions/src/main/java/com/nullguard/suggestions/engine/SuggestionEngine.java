package com.nullguard.suggestions.engine;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;

import java.util.List;
import java.util.Map;

public interface SuggestionEngine {
    List<Suggestion> generate(
            ProjectModel project,
            Map<String, AdjustedRiskModel> riskMap,
            GlobalCallGraph callGraph
    );
}
