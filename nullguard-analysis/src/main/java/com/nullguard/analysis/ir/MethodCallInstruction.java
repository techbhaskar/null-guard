package com.nullguard.analysis.ir;
import java.util.Objects;
public record MethodCallInstruction(String id, String cfgNodeId, int lineNumber, String methodCall) implements Instruction {
    public MethodCallInstruction { Objects.requireNonNull(id); Objects.requireNonNull(cfgNodeId); Objects.requireNonNull(methodCall); }
}
