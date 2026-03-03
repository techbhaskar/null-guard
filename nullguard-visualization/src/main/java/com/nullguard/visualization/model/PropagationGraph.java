package com.nullguard.visualization.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class PropagationGraph {
    private final Map<String, GraphNode> nodes;
    private final Set<GraphEdge> edges;

    public PropagationGraph(LinkedHashMap<String, GraphNode> nodes, LinkedHashSet<GraphEdge> edges) {
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.edges = Collections.unmodifiableSet(new LinkedHashSet<>(edges));
    }

    public Map<String, GraphNode> getNodes() {
        return nodes;
    }

    public Set<GraphEdge> getEdges() {
        return edges;
    }
}
