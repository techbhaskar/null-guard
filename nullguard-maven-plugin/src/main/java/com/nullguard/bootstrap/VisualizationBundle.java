package com.nullguard.bootstrap;

import java.util.Objects;

/**
 * VisualizationBundle – encapsulates all visualization export artifacts
 * produced by the visualization step of the pipeline.
 *
 * Holds the DOT (Graphviz) representation and the JSON graph serialization
 * of the risk propagation graph.  Immutable after construction.
 */
public final class VisualizationBundle {

    private final String jsonGraph;
    private final String dotGraph;

    public VisualizationBundle(String jsonGraph, String dotGraph) {
        this.jsonGraph = Objects.requireNonNull(jsonGraph, "jsonGraph must not be null");
        this.dotGraph  = Objects.requireNonNull(dotGraph,  "dotGraph must not be null");
    }

    /** Full JSON graph export (includes summary + risk nodes). */
    public String getJsonGraph() { return jsonGraph; }

    /** DOT/Graphviz graph export for visual rendering. */
    public String getDotGraph()  { return dotGraph; }
}
