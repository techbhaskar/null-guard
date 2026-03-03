package com.nullguard.analysis.ir;
import java.util.Objects;
public record ConditionalInstruction(String id, String cfgNodeId, int lineNumber, String condition) implements Instruction {
    public ConditionalInstruction { Objects.requireNonNull(id); Objects.requireNonNull(cfgNodeId); Objects.requireNonNull(condition); }
}
