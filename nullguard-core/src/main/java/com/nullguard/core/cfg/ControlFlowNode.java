package com.nullguard.core.cfg;

import java.util.Objects;

public final class ControlFlowNode {
    private final String id;
    private final NodeType type;
    private final String sourceCode;
    private final int lineNumber;
    private final com.nullguard.core.model.SourceLocation sourceRange;

    public ControlFlowNode(String id, NodeType type, String sourceCode, int lineNumber) {
        this(id, type, sourceCode, lineNumber, null);
    }

    public ControlFlowNode(String id, NodeType type, String sourceCode, int lineNumber,
                           com.nullguard.core.model.SourceLocation sourceRange) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.type = Objects.requireNonNull(type, "NodeType cannot be null");
        this.sourceCode = Objects.requireNonNull(sourceCode, "SourceCode cannot be null");
        this.lineNumber = lineNumber;
        this.sourceRange = sourceRange;
    }

    public String getId() { return id; }
    public NodeType getType() { return type; }
    public String getSourceCode() { return sourceCode; }
    public int getLineNumber() { return lineNumber; }
    public java.util.Optional<com.nullguard.core.model.SourceLocation> getSourceRange() { return java.util.Optional.ofNullable(sourceRange); }

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
