package com.nullguard.visualization.graph;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.RiskLevel;
import com.nullguard.visualization.model.GraphEdge;
import com.nullguard.visualization.model.GraphNode;
import com.nullguard.visualization.model.PropagationGraph;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class DefaultPropagationGraphBuilder implements PropagationGraphBuilder {

    @Override
    public PropagationGraph build(ProjectModel project, GlobalCallGraph callGraph, Map<String, AdjustedRiskModel> adjustedRisks) {
        LinkedHashMap<String, GraphNode> nodes = new LinkedHashMap<>();
        LinkedHashSet<GraphEdge> edges = new LinkedHashSet<>();

        for (Map.Entry<String, AdjustedRiskModel> entry : adjustedRisks.entrySet()) {
            String methodId = entry.getKey();
            AdjustedRiskModel riskModel = entry.getValue();
            boolean external = callGraph.isExternal(methodId);
            
            GraphNode node = new GraphNode(
                methodId,
                riskModel.getIntrinsicRisk(),
                riskModel.getPropagatedRisk(),
                riskModel.getAdjustedRisk(),
                riskModel.getRiskLevel(),
                external
            );
            nodes.put(methodId, node);
        }

        for (String externalNode : callGraph.getExternalNodes()) {
            if (!nodes.containsKey(externalNode)) {
                nodes.put(externalNode, new GraphNode(
                    externalNode, 0.0, 0.0, 0.0, RiskLevel.LOW, true
                ));
            }
        }

        for (Map.Entry<String, Set<String>> entry : callGraph.getOutgoing().entrySet()) {
            String from = entry.getKey();
            for (String to : entry.getValue()) {
                edges.add(new GraphEdge(from, to));
            }
        }

        return new PropagationGraph(nodes, edges);
    }
}
