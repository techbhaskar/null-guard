package com.nullguard.visualization.graph;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.visualization.model.PropagationGraph;

import java.util.Map;

public interface PropagationGraphBuilder {
    PropagationGraph build(ProjectModel project, GlobalCallGraph callGraph, Map<String, AdjustedRiskModel> adjustedRisks);
}
