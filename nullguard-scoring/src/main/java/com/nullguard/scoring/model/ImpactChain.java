package com.nullguard.scoring.model;

import java.util.List;
import java.util.Collections;

/**
 * Represents a single failure propagation path from a method upwards to an entry point.
 */
public record ImpactChain(List<String> path, String entryPoint, String severity) {
    public ImpactChain {
        path = Collections.unmodifiableList(path);
    }
}
