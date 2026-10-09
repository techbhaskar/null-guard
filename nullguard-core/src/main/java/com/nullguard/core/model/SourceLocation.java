package com.nullguard.core.model;

/** One-based source range. Paths are relative to the analyzed source root. */
public record SourceLocation(String path, int startLine, int startColumn, int endLine, int endColumn) {}
