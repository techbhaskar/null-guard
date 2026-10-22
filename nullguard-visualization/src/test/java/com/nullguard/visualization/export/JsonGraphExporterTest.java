package com.nullguard.visualization.export;

import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.core.risk.RiskLevel;
import com.nullguard.visualization.model.GraphEdge;
import com.nullguard.visualization.model.GraphNode;
import com.nullguard.visualization.model.PropagationGraph;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonGraphExporterTest {

    @Test void streamingLeavesTargetOpenAndScriptEscapingRoundTrips() throws Exception {
        var exporter = new JsonGraphExporter();
        var payload = java.util.Map.of("value", "</script>&\u2028\u2029");
        var writer = new java.io.StringWriter();
        exporter.writeForScript(payload, writer);
        String script = writer.toString();
        org.junit.jupiter.api.Assertions.assertFalse(script.contains("</script>"));
        org.junit.jupiter.api.Assertions.assertEquals(payload.get("value"), new com.fasterxml.jackson.databind.ObjectMapper().readTree(script).get("value").asText());
        var stream = new java.io.ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("caller owns the stream"); }
        };
        exporter.write(payload, stream);
        org.junit.jupiter.api.Assertions.assertEquals(exporter.toCompactString(payload), stream.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void failedOutputIsReportedToCaller() {
        var exporter = new JsonGraphExporter();
        var broken = new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("disk full"); }
        };
        org.junit.jupiter.api.Assertions.assertThrows(com.nullguard.visualization.exception.VisualizationException.class,
                () -> exporter.write(java.util.Map.of("key", "value"), broken));
        var writer = new java.io.Writer() {
            @Override public void write(char[] chars, int start, int length) throws java.io.IOException { throw new java.io.IOException("disk full"); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        org.junit.jupiter.api.Assertions.assertThrows(com.nullguard.visualization.exception.VisualizationException.class,
                () -> exporter.writeForScript(java.util.Map.of("key", "value"), writer));
    }

    @Test
    void testJsonStructureValidation() {
        LinkedHashMap<String, GraphNode> nodes = new LinkedHashMap<>();
        nodes.put("A", new GraphNode("A", 10.0, 5.0, 0.0, 0.0, 15.0, RiskLevel.LOW, false, java.util.List.of()));
        LinkedHashSet<GraphEdge> edges = new LinkedHashSet<>();
        edges.add(new GraphEdge("A", "B"));
        
        PropagationGraph graph = new PropagationGraph(nodes, edges);
        ProjectRiskSummary summary = new ProjectRiskSummary(90.0, "A", 15.0, 15.0, 0.0, 1, 0, 0.0, 0);

        JsonGraphExporter exporter = new JsonGraphExporter();
        String json = exporter.export(graph, summary);

        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"graph\""));
        assertTrue(json.contains("\"methodId\" : \"A\""));
    }
}
