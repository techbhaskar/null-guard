package com.nullguard.visualization.model;

import java.util.Objects;

public final class GraphEdge {
    private final String from;
    private final String to;

    public GraphEdge(String from, String to) {
        this.from = Objects.requireNonNull(from, "from cannot be null");
        this.to = Objects.requireNonNull(to, "to cannot be null");
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GraphEdge graphEdge = (GraphEdge) o;
        return Objects.equals(from, graphEdge.from) &&
                Objects.equals(to, graphEdge.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }
}
