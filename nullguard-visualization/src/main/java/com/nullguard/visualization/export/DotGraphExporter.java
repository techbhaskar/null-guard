package com.nullguard.visualization.export;

import com.nullguard.visualization.heatmap.HeatmapOverlay;
import com.nullguard.visualization.model.GraphEdge;
import com.nullguard.visualization.model.GraphNode;
import com.nullguard.visualization.model.PropagationGraph;

/**
 * Renders a {@link PropagationGraph} as Graphviz DOT.
 *
 * <h3>Escaping</h3>
 * {@link #escape(String)} used to be {@code str.replace("\"", "\\\"")} — a single substitution
 * that did not escape backslashes, and did not escape them <em>first</em>. A method id ending in
 * a backslash therefore emitted {@code "foo\"}, where the intended closing quote became an
 * escaped literal quote and corrupted the remainder of the file. Node ids come from parsed
 * source signatures, so this was reachable rather than theoretical. Backslash is now escaped
 * before the quote, and newlines and carriage returns are escaped too.
 */
public class DotGraphExporter {

    private static final HeatmapOverlay HEATMAP = new HeatmapOverlay();

    public String export(PropagationGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph PropagationGraph {\n");
        sb.append("  node [style=filled];\n");

        for (GraphNode node : graph.getNodes().values()) {
            // Typed lookup: a new RiskLevel constant without a colour is now visible as a gap
            // rather than silently rendering white via a failed string-key match.
            String color = HEATMAP.colorFor(node.getRiskLevel());
            String externalStr = node.isExternal() ? ", shape=box" : "";
            sb.append(String.format("  \"%s\" [color=\"%s\"%s];%n",
                    escape(node.getMethodId()), color, externalStr));
        }

        for (GraphEdge edge : graph.getEdges()) {
            sb.append(String.format("  \"%s\" -> \"%s\";%n",
                    escape(edge.getFrom()), escape(edge.getTo())));
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Escapes a value for use inside a double-quoted DOT identifier.
     *
     * <p>Order matters: the backslash substitution must run first, otherwise it would also
     * escape the backslashes introduced by the quote substitution.
     */
    static String escape(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
}
