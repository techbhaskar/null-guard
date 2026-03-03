package com.nullguard.core.cfg;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

public final class ControlFlowModel {
    private final String methodSignature;
    private final LinkedHashMap<String, ControlFlowNode> nodes;
    private final LinkedHashSet<ControlFlowEdge> edges;
    private final String entryNodeId;
    private final String exitNodeId;

    public ControlFlowModel(String methodSignature, 
                            LinkedHashMap<String, ControlFlowNode> nodes, 
                            LinkedHashSet<ControlFlowEdge> edges, 
                            String entryNodeId, 
                            String exitNodeId) {
        this.methodSignature = Objects.requireNonNull(methodSignature);
        this.nodes = new LinkedHashMap<>(nodes);
        this.edges = new LinkedHashSet<>(edges);
        this.entryNodeId = Objects.requireNonNull(entryNodeId);
        this.exitNodeId = Objects.requireNonNull(exitNodeId);
    }

    public String getMethodSignature() { return methodSignature; }
    public Map<String, ControlFlowNode> getNodes() { return Collections.unmodifiableMap(nodes); }
    public Set<ControlFlowEdge> getEdges() { return Collections.unmodifiableSet(edges); }
    public String getEntryNodeId() { return entryNodeId; }
    public String getExitNodeId() { return exitNodeId; }
}
