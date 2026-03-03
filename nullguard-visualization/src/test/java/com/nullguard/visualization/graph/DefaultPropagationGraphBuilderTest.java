package com.nullguard.visualization.graph;

import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.RiskLevel;
import com.nullguard.visualization.model.PropagationGraph;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPropagationGraphBuilderTest {

    @Test
    void testDeterministicGraphBuilding() {
        ProjectModel project = ProjectModel.builder().projectName("test").build();

        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        outgoing.put("A", new LinkedHashSet<>(Collections.singletonList("B")));
        outgoing.put("B", new LinkedHashSet<>(Collections.singletonList("C")));

        GlobalCallGraph callGraph = new GlobalCallGraph(outgoing, new LinkedHashMap<>(), new LinkedHashSet<>(Collections.singletonList("C")));

        Map<String, AdjustedRiskModel> risks = new LinkedHashMap<>();
        risks.put("A", new AdjustedRiskModel(10, 0, 10, RiskLevel.LOW));
        risks.put("B", new AdjustedRiskModel(50, 0, 50, RiskLevel.MEDIUM));

        DefaultPropagationGraphBuilder builder = new DefaultPropagationGraphBuilder();
        PropagationGraph graph = builder.build(project, callGraph, risks);

        assertEquals(3, graph.getNodes().size());
        assertEquals(2, graph.getEdges().size());
        assertTrue(graph.getNodes().get("C").isExternal());
        assertEquals(RiskLevel.LOW, graph.getNodes().get("C").getRiskLevel()); // Default for external absent in risks
    }
}
