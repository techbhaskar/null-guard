package com.nullguard.bootstrap;

/**
 * ArchitectureViolationException – thrown when any of the three governance
 * checks in {@link ArchitectureValidator} detect a structural violation.
 *
 * Unchecked so that callers (CLI, Mojo) can handle it at the outermost boundary.
 */
public final class ArchitectureViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ArchitectureViolationException(String message) {
        super(message);
    }

    public ArchitectureViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
