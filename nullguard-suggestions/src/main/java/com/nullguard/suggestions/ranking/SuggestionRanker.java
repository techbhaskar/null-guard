package com.nullguard.suggestions.ranking;

import com.nullguard.suggestions.model.Suggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SuggestionRanker {
    public List<Suggestion> rank(List<Suggestion> suggestions) {
        List<Suggestion> sorted = new ArrayList<>(suggestions);
        sorted.sort(Comparator.comparingDouble(Suggestion::getFinalScore).reversed());
        return sorted;
    }
}
