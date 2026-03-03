package com.nullguard.analysis.ir;
import java.util.Objects;
public record DereferenceInstruction(String id, String cfgNodeId, int lineNumber, String variableName) implements Instruction {
    public DereferenceInstruction { Objects.requireNonNull(id); Objects.requireNonNull(cfgNodeId); Objects.requireNonNull(variableName); }
}
