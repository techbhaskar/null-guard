package com.nullguard.analysis.ir;
public interface Instruction {
    String id();
    String cfgNodeId();
    int lineNumber();
}
