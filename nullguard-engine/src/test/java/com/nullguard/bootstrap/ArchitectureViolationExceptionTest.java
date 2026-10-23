package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ArchitectureViolationExceptionTest {

    @Test
    void storesMessageAndCauseForTheFullConstructor() {
        var cause = new IllegalStateException("root cause");
        var exception = new ArchitectureViolationException("architecture failed", cause);

        assertEquals("architecture failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void storesOnlyTheMessageForTheSingleArgumentConstructor() {
        var exception = new ArchitectureViolationException("message only");

        assertEquals("message only", exception.getMessage());
        assertSame(null, exception.getCause());
    }
}
