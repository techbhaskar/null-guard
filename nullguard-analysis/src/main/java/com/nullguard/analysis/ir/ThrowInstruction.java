package com.nullguard.analysis.ir;
import java.util.Objects;
public record ThrowInstruction(String id, String cfgNodeId, int lineNumber, String exception) implements Instruction {
    public ThrowInstruction { Objects.requireNonNull(id); Objects.requireNonNull(cfgNodeId); Objects.requireNonNull(exception); }
}
