package com.nullguard.visualization.export;

import com.nullguard.scoring.model.RiskLevel;
import com.nullguard.visualization.model.GraphEdge;
import com.nullguard.visualization.model.GraphNode;
import com.nullguard.visualization.model.PropagationGraph;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DotGraphExporterTest {

    @Test
    void testDotColorMappingCorrectness() {
        LinkedHashMap<String, GraphNode> nodes = new LinkedHashMap<>();
        nodes.put("A", new GraphNode("A", 10.0, 5.0, 15.0, RiskLevel.LOW, false));
        nodes.put("B", new GraphNode("B", 80.0, 5.0, 85.0, RiskLevel.CRITICAL, true));
        
        LinkedHashSet<GraphEdge> edges = new LinkedHashSet<>();
        edges.add(new GraphEdge("A", "B"));
        
        PropagationGraph graph = new PropagationGraph(nodes, edges);

        DotGraphExporter exporter = new DotGraphExporter();
        String dot = exporter.export(graph);

        assertTrue(dot.contains("digraph PropagationGraph"));
        assertTrue(dot.contains("\"A\" [color=\"green\"]"));
        assertTrue(dot.contains("\"B\" [color=\"red\", shape=box]"));
        assertTrue(dot.contains("\"A\" -> \"B\""));
    }
}
