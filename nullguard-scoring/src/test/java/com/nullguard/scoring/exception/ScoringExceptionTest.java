package com.nullguard.scoring.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScoringExceptionTest {

    @Test
    void storesMessageAndCauseForTheFullConstructor() {
        var cause = new IllegalStateException("root cause");
        var exception = new ScoringException("score failed", cause);

        assertEquals("score failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void storesOnlyTheMessageForTheSingleArgumentConstructor() {
        var exception = new ScoringException("message only");

        assertEquals("message only", exception.getMessage());
        assertNull(exception.getCause());
    }
}
