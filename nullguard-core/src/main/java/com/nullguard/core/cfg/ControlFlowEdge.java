package com.nullguard.core.cfg;

import java.util.Objects;

public final class ControlFlowEdge {
    private final String fromNodeId;
    private final String toNodeId;
    private final EdgeType type;

    public ControlFlowEdge(String fromNodeId, String toNodeId, EdgeType type) {
        this.fromNodeId = Objects.requireNonNull(fromNodeId, "fromNodeId cannot be null");
        this.toNodeId = Objects.requireNonNull(toNodeId, "toNodeId cannot be null");
        this.type = Objects.requireNonNull(type, "EdgeType cannot be null");
    }

    public String getFromNodeId() { return fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public EdgeType getType() { return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ControlFlowEdge that = (ControlFlowEdge) o;
        return fromNodeId.equals(that.fromNodeId) && toNodeId.equals(that.toNodeId) && type == that.type;
    }

    @Override
    public int hashCode() { return Objects.hash(fromNodeId, toNodeId, type); }
}
