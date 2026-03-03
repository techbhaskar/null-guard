package com.nullguard.analysis.ir;
import java.util.Objects;
public record AssignmentInstruction(String id, String cfgNodeId, int lineNumber, String target, String source) implements Instruction {
    public AssignmentInstruction { Objects.requireNonNull(id); Objects.requireNonNull(cfgNodeId); Objects.requireNonNull(target); Objects.requireNonNull(source); }
}
