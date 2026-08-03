package com.nullguard.analysis.ir;

import java.util.Objects;

/**
 * A call site.
 *
 * @param methodCall the callee text as written, e.g. {@code "userRepository.findByEmail"}
 * @param argCount   number of arguments at the call site, or {@link #UNKNOWN_ARG_COUNT} when
 *                   it could not be determined. Used by the call-graph resolver to discriminate
 *                   overloads: without it, {@code foo(int)} and {@code foo(String, String)} are
 *                   indistinguishable and the first one iterated wins.
 */
public record MethodCallInstruction(String id, String cfgNodeId, int lineNumber,
                                    String methodCall, int argCount) implements Instruction {

    /** Sentinel for "the extractor could not count the arguments". */
    public static final int UNKNOWN_ARG_COUNT = -1;

    public MethodCallInstruction {
        Objects.requireNonNull(id);
        Objects.requireNonNull(cfgNodeId);
        Objects.requireNonNull(methodCall);
    }

    /** Back-compatible constructor for callers that have no argument-count information. */
    public MethodCallInstruction(String id, String cfgNodeId, int lineNumber, String methodCall) {
        this(id, cfgNodeId, lineNumber, methodCall, UNKNOWN_ARG_COUNT);
    }
}
