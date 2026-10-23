package com.nullguard.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CoreAnalysisExceptionTest {

    @Test
    void storesMessageAndCauseForTheFullConstructor() {
        var cause = new IllegalStateException("root cause");
        var exception = new CoreAnalysisException("analysis failed", cause);

        assertEquals("analysis failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void storesOnlyTheMessageForTheSingleArgumentConstructor() {
        var exception = new CoreAnalysisException("message only");

        assertEquals("message only", exception.getMessage());
        assertNull(exception.getCause());
    }
}
