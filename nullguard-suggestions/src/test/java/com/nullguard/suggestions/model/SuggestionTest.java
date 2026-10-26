package com.nullguard.suggestions.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SuggestionTest {
    @Test
    void exposesValuesAndImplementsValueEquality() {
        Suggestion suggestion = new Suggestion("OrderService.save", SuggestionType.ADD_NULL_GUARD,
                "Validate the input", 0.4, 0.8, 1.2, 0.384);
        Suggestion equal = new Suggestion("OrderService.save", SuggestionType.ADD_NULL_GUARD,
                "Validate the input", 0.4, 0.8, 1.2, 0.384);
        Suggestion different = new Suggestion("OrderService.save", SuggestionType.ADD_NULL_GUARD,
                "Use a stronger contract", 0.4, 0.8, 1.2, 0.384);

        assertEquals("OrderService.save", suggestion.getMethodId());
        assertEquals(SuggestionType.ADD_NULL_GUARD, suggestion.getSuggestionType());
        assertEquals("Validate the input", suggestion.getMessage());
        assertEquals(0.4, suggestion.getRiskReductionEstimate());
        assertEquals(0.8, suggestion.getConfidence());
        assertEquals(1.2, suggestion.getPriorityWeight());
        assertEquals(0.384, suggestion.getFinalScore());
        assertEquals(suggestion, suggestion);
        assertEquals(suggestion, equal);
        assertEquals(suggestion.hashCode(), equal.hashCode());
        assertNotEquals(suggestion, different);
        assertNotEquals(suggestion, null);
        assertNotEquals(suggestion, "not a suggestion");
        assertNotEquals(suggestion, new Suggestion("other", SuggestionType.ADD_NULL_GUARD,
                "Validate the input", 0.4, 0.8, 1.2, 0.384));
        assertNotEquals(suggestion, new Suggestion("OrderService.save", SuggestionType.STRENGTHEN_CONTRACT,
                "Validate the input", 0.4, 0.8, 1.2, 0.384));
        assertNotEquals(suggestion, new Suggestion("OrderService.save", SuggestionType.ADD_NULL_GUARD,
                "Validate the input", 0.5, 0.8, 1.2, 0.384));
        assertNotEquals(suggestion, new Suggestion("OrderService.save", SuggestionType.ADD_NULL_GUARD,
                "Validate the input", 0.4, 0.9, 1.2, 0.384));
        assertNotEquals(suggestion, new Suggestion("OrderService.save", SuggestionType.ADD_NULL_GUARD,
                "Validate the input", 0.4, 0.8, 1.3, 0.384));
        assertNotEquals(suggestion, new Suggestion("OrderService.save", SuggestionType.ADD_NULL_GUARD,
                "Validate the input", 0.4, 0.8, 1.2, 0.385));
    }

    @Test
    void rejectsMissingRequiredValues() {
        assertThrows(NullPointerException.class, () -> new Suggestion(
                null, SuggestionType.ADD_NULL_GUARD, "message", 0.1, 0.5, 1, 0.05));
        assertThrows(NullPointerException.class, () -> new Suggestion(
                "method", null, "message", 0.1, 0.5, 1, 0.05));
        assertThrows(NullPointerException.class, () -> new Suggestion(
                "method", SuggestionType.ADD_NULL_GUARD, null, 0.1, 0.5, 1, 0.05));
    }
}
