package com.nullguard.core.model;

/**
 * Represents an AST-level static code analysis finding discovered on a method.
 */
public record MethodFinding(
        String ruleId,
        String severity,
        String message,
        int line
) {}
