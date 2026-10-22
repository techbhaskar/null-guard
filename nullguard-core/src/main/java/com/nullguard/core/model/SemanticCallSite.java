package com.nullguard.core.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A source call site enriched with a symbol-solver target when resolution succeeded. */
public final class SemanticCallSite {
    private final String writtenName;
    private final int argumentCount;
    private final ResolvedCallTarget resolvedTarget;
    private final String expression;
    private final int lineNumber;
    private final String resolutionFailure;
    private final Set<Integer> primitiveArguments;

    public SemanticCallSite(String writtenName,
                            int argumentCount,
                            ResolvedCallTarget resolvedTarget) {
        this(writtenName, argumentCount, resolvedTarget, "");
    }

    public SemanticCallSite(String writtenName, int argumentCount, ResolvedCallTarget resolvedTarget, String expression) {
        this(writtenName, argumentCount, resolvedTarget, expression, -1);
    }

    public SemanticCallSite(String writtenName, int argumentCount, ResolvedCallTarget resolvedTarget, String expression, int lineNumber) {
        this(writtenName, argumentCount, resolvedTarget, expression, lineNumber, "");
    }

    public SemanticCallSite(String writtenName, int argumentCount, ResolvedCallTarget resolvedTarget, String expression, int lineNumber, String resolutionFailure) {
        this(writtenName, argumentCount, resolvedTarget, expression, lineNumber, resolutionFailure, Set.of());
    }

    public SemanticCallSite(String writtenName, int argumentCount, ResolvedCallTarget resolvedTarget, String expression,
                            int lineNumber, String resolutionFailure, Set<Integer> primitiveArguments) {
        this.writtenName = Objects.requireNonNull(writtenName, "writtenName");
        this.argumentCount = argumentCount;
        this.resolvedTarget = resolvedTarget;
        this.expression = expression;
        this.lineNumber = lineNumber;
        this.resolutionFailure = Objects.requireNonNull(resolutionFailure, "resolutionFailure");
        this.primitiveArguments = Set.copyOf(primitiveArguments);
        if (this.primitiveArguments.stream().anyMatch(i -> i < 0 || i >= argumentCount))
            throw new IllegalArgumentException("Primitive argument index is outside call arity");
    }

    public String getWrittenName() {
        return writtenName;
    }

    public int getArgumentCount() {
        return argumentCount;
    }
    public String getExpression() { return expression; }
    public int getLineNumber() { return lineNumber; }
    public String getResolutionFailure() { return resolutionFailure; }
    public boolean hasPrimitiveArgument(int index) { return primitiveArguments.contains(index); }

    public Optional<ResolvedCallTarget> getResolvedTarget() {
        return Optional.ofNullable(resolvedTarget);
    }
}
