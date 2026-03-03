package com.nullguard.visualization.export;

import com.nullguard.visualization.heatmap.HeatmapOverlay;
import com.nullguard.visualization.model.GraphEdge;
import com.nullguard.visualization.model.GraphNode;
import com.nullguard.visualization.model.PropagationGraph;

import java.util.Map;

public class DotGraphExporter {

    public String export(PropagationGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph PropagationGraph {\n");
        sb.append("  node [style=filled];\n");

        HeatmapOverlay heatmap = new HeatmapOverlay();
        Map<String, String> colors = heatmap.riskColorMapping();

        for (GraphNode node : graph.getNodes().values()) {
            String color = colors.getOrDefault(node.getRiskLevel().name(), "white");
            String externalStr = node.isExternal() ? ", shape=box" : "";
            sb.append(String.format("  \"%s\" [color=\"%s\"%s];\n", 
                    escape(node.getMethodId()), color, externalStr));
        }

        for (GraphEdge edge : graph.getEdges()) {
            sb.append(String.format("  \"%s\" -> \"%s\";\n", 
                    escape(edge.getFrom()), escape(edge.getTo())));
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String escape(String str) {
        return str.replace("\"", "\\\"");
    }
}
