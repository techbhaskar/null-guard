package com.nullguard.core.cfg;

import java.util.Objects;

public final class ControlFlowNode {
    private final String id;
    private final NodeType type;
    private final String sourceCode;
    private final int lineNumber;

    public ControlFlowNode(String id, NodeType type, String sourceCode, int lineNumber) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.type = Objects.requireNonNull(type, "NodeType cannot be null");
        this.sourceCode = Objects.requireNonNull(sourceCode, "SourceCode cannot be null");
        this.lineNumber = lineNumber;
    }

    public String getId() { return id; }
    public NodeType getType() { return type; }
    public String getSourceCode() { return sourceCode; }
    public int getLineNumber() { return lineNumber; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ControlFlowNode that = (ControlFlowNode) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
