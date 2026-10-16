package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nullguard.visualization.export.JsonGraphExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reports are streamed from the object model and embedded once, script-safely. */
class ReportStreamingTest {

    @Test void scriptEmbeddingCannotCloseTheScriptElement() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("message", "</script><img src=x onerror=alert(1)>& ");
        report.put("score", Double.NaN);
        var bundle = new VisualizationBundle(report, new JsonGraphExporter(), "", "", List.of());

        StringWriter out = new StringWriter();
        bundle.writeJsonForScript(out);
        String embedded = out.toString();
        assertFalse(embedded.contains("<"), embedded);
        assertFalse(embedded.contains(">"), embedded);
        assertFalse(embedded.contains(" "), embedded);
        // Still valid JSON that decodes back to the original text.
        var tree = new ObjectMapper().readTree(embedded);
        assertEquals("</script><img src=x onerror=alert(1)>& ", tree.get("message").asText());
        assertEquals("NaN", tree.get("score").asText());
    }

    @Test void streamedFileMatchesInMemoryReport(@TempDir Path dir) throws Exception {
        var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(PipelineIntegrationTest.SOURCES);
        Path json = dir.resolve("report.json");
        result.getVisualizations().writeJson(json);
        var mapper = new ObjectMapper();
        var streamed = mapper.readTree(json.toFile());
        assertEquals(mapper.readTree(result.getVisualizations().getJsonGraph()), streamed);
        for (String section : List.of("summary", "graph", "schemaVersion", "findings", "apiEndpoints", "hotspots", "suggestions"))
            assertTrue(streamed.has(section), section);
    }

    @Test void dashboardEmbedsTheReportExactlyOnce(@TempDir Path dir) throws Exception {
        var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(PipelineIntegrationTest.SOURCES);
        Path html = dir.resolve("dashboard.html");
        HtmlDashboardRenderer.write(html, result, "20260101_000000", "orders<service>");
        String page = Files.readString(html);
        assertEquals(1, page.split("\"schemaVersion\"", -1).length - 1, "report must be embedded once");
        assertFalse(page.contains("Object.assign"), "side tables are already part of the report");
        assertTrue(page.contains("orders&lt;service&gt;"));
        assertEquals(1, page.split("</script>", -1).length - 1, "only the real closing tag");
    }
}
