package com.nullguard.bootstrap;

import com.nullguard.core.model.SourceLocation;

/** Versioned report finding; rule identifiers remain stable across releases. */
public record Finding(String ruleId, String severity, String methodId, String message, SourceLocation location) {}
