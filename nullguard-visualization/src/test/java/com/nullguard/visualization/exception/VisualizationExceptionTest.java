package com.nullguard.visualization.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class VisualizationExceptionTest {

    @Test
    void storesMessageAndCauseForTheFullConstructor() {
        var cause = new IllegalStateException("root cause");
        var exception = new VisualizationException("graph failed", cause);

        assertEquals("graph failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void storesOnlyTheMessageForTheSingleArgumentConstructor() {
        var exception = new VisualizationException("message only");

        assertEquals("message only", exception.getMessage());
        assertNull(exception.getCause());
    }
}
