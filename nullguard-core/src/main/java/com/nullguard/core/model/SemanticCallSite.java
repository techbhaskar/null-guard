package com.nullguard.core.model;

import java.util.Objects;
import java.util.Optional;

/** A source call site enriched with a symbol-solver target when resolution succeeded. */
public final class SemanticCallSite {
    private final String writtenName;
    private final int argumentCount;
    private final ResolvedCallTarget resolvedTarget;
    private final String expression;
    private final int lineNumber;
    private final String resolutionFailure;

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
        this.writtenName = Objects.requireNonNull(writtenName, "writtenName");
        this.argumentCount = argumentCount;
        this.resolvedTarget = resolvedTarget;
        this.expression = expression;
        this.lineNumber = lineNumber;
        this.resolutionFailure = Objects.requireNonNull(resolutionFailure, "resolutionFailure");
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

    public Optional<ResolvedCallTarget> getResolvedTarget() {
        return Optional.ofNullable(resolvedTarget);
    }
}
