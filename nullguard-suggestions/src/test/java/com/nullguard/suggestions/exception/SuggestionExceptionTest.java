package com.nullguard.suggestions.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SuggestionExceptionTest {

    @Test
    void storesMessageAndCauseForTheFullConstructor() {
        var cause = new IllegalStateException("root cause");
        var exception = new SuggestionException("suggestion failed", cause);

        assertEquals("suggestion failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void storesOnlyTheMessageForTheSingleArgumentConstructor() {
        var exception = new SuggestionException("message only");

        assertEquals("message only", exception.getMessage());
        assertNull(exception.getCause());
    }
}
