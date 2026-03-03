package com.nullguard.suggestions.model;

import java.util.Objects;

public final class Suggestion {
    private final String methodId;
    private final SuggestionType suggestionType;
    private final String message;
    private final double riskReductionEstimate;
    private final double confidence;
    private final double priorityWeight;
    private final double finalScore;

    public Suggestion(String methodId, SuggestionType suggestionType, String message,
                      double riskReductionEstimate, double confidence, double priorityWeight, double finalScore) {
        this.methodId = Objects.requireNonNull(methodId);
        this.suggestionType = Objects.requireNonNull(suggestionType);
        this.message = Objects.requireNonNull(message);
        this.riskReductionEstimate = riskReductionEstimate;
        this.confidence = confidence;
        this.priorityWeight = priorityWeight;
        this.finalScore = finalScore;
    }

    public String getMethodId() {
        return methodId;
    }

    public SuggestionType getSuggestionType() {
        return suggestionType;
    }

    public String getMessage() {
        return message;
    }

    public double getRiskReductionEstimate() {
        return riskReductionEstimate;
    }

    public double getConfidence() {
        return confidence;
    }

    public double getPriorityWeight() {
        return priorityWeight;
    }

    public double getFinalScore() {
        return finalScore;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Suggestion that = (Suggestion) o;
        return Double.compare(that.riskReductionEstimate, riskReductionEstimate) == 0 &&
               Double.compare(that.confidence, confidence) == 0 &&
               Double.compare(that.priorityWeight, priorityWeight) == 0 &&
               Double.compare(that.finalScore, finalScore) == 0 &&
               methodId.equals(that.methodId) &&
               suggestionType == that.suggestionType &&
               message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(methodId, suggestionType, message, riskReductionEstimate, confidence, priorityWeight, finalScore);
    }
}
