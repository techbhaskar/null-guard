package com.nullguard.core.model;

import java.util.List;

/** Source accounting, not a claim about runtime or dependency-body coverage. */
public record ParseDiagnostics(int discoveredFiles, int parsedFiles, int problemFiles,
                               int discoveredExecutables, List<String> problemPaths) {
    public ParseDiagnostics { problemPaths = List.copyOf(problemPaths); }
    public static ParseDiagnostics unavailable() { return new ParseDiagnostics(0, 0, 0, 0, List.of()); }
}
