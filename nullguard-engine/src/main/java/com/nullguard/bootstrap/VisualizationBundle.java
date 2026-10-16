package com.nullguard.bootstrap;

import com.nullguard.visualization.export.JsonGraphExporter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * VisualizationBundle – encapsulates all visualization export artifacts
 * produced by the visualization step of the pipeline.
 *
 * <p>The JSON report is held as its object model and serialised on demand. Holding it as a
 * pre-rendered String (and re-parsing it to add sections) kept several full copies of the
 * report alive at once and exhausted the heap on real services.
 */
public final class VisualizationBundle {

    private final Object report;
    private final String jsonGraph;
    private final JsonGraphExporter exporter;
    private final String dotGraph;
    private final String sarif;
    private final java.util.List<Finding> findings;

    public VisualizationBundle(String jsonGraph, String dotGraph) {
        this(jsonGraph, dotGraph, "", java.util.List.of());
    }
    public VisualizationBundle(String jsonGraph, String dotGraph, String sarif, java.util.List<Finding> findings) {
        this(null, Objects.requireNonNull(jsonGraph, "jsonGraph must not be null"), null, dotGraph, sarif, findings);
    }
    /** Report kept as an object model; serialised only when written. */
    public VisualizationBundle(Object report, JsonGraphExporter exporter, String dotGraph, String sarif, java.util.List<Finding> findings) {
        this(Objects.requireNonNull(report, "report must not be null"), null,
                Objects.requireNonNull(exporter, "exporter must not be null"), dotGraph, sarif, findings);
    }
    private VisualizationBundle(Object report, String jsonGraph, JsonGraphExporter exporter,
                                String dotGraph, String sarif, java.util.List<Finding> findings) {
        this.report = report;
        this.jsonGraph = jsonGraph;
        this.exporter = exporter;
        this.dotGraph  = Objects.requireNonNull(dotGraph,  "dotGraph must not be null");
        this.sarif = Objects.requireNonNull(sarif);
        this.findings = java.util.List.copyOf(findings);
    }

    /**
     * Full JSON report as one String. Materialises the whole document; report writers should
     * use {@link #writeJson(Path)} instead.
     */
    public String getJsonGraph() { return jsonGraph != null ? jsonGraph : exporter.toCompactString(report); }

    /** Streams the JSON report to {@code file}. */
    public void writeJson(Path file) throws IOException {
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            if (jsonGraph != null) out.write(jsonGraph.getBytes(StandardCharsets.UTF_8));
            else exporter.write(report, out);
        }
    }

    /** Streams the OpenAPI 3.0 specification to {@code file} if present in the report. */
    public void writeOpenApiJson(Path file) throws IOException {
        if (report instanceof java.util.Map<?, ?> map && map.get("openApiSpec") != null && exporter != null) {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
                exporter.write(map.get("openApiSpec"), out);
            }
        }
    }

    /** Streams the report as JSON that is safe inside an HTML script element. */
    public void writeJsonForScript(Writer out) throws IOException {
        if (report != null) { exporter.writeForScript(report, out); return; }
        out.write(jsonGraph.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"));
    }

    /** Streams any value (e.g. a dashboard side table) as script-safe JSON. */
    public static void writeValueForScript(Object value, Writer out) {
        SCRIPT_JSON.writeForScript(value, out);
    }
    private static final JsonGraphExporter SCRIPT_JSON = new JsonGraphExporter();

    /** DOT/Graphviz graph export for visual rendering. */
    public String getDotGraph()  { return dotGraph; }
    public String getSarif() { return sarif; }
    public java.util.List<Finding> getFindings() { return findings; }
}
