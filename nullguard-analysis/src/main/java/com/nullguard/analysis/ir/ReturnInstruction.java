package com.nullguard.analysis.ir;
import java.util.Objects;
public record ReturnInstruction(String id, String cfgNodeId, int lineNumber, String returnValue) implements Instruction {
    public ReturnInstruction { Objects.requireNonNull(id); Objects.requireNonNull(cfgNodeId); Objects.requireNonNull(returnValue); }
}
