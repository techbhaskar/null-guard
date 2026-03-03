package com.nullguard.scoring.config;

public final class ScoringConfig {
    private final double decayFactor;
    private final double convergenceThreshold;
    private final int maxIterations;
    private final int highRiskThreshold;
    private final double externalPenaltyMultiplier;

    private ScoringConfig(Builder builder) {
        this.decayFactor = builder.decayFactor;
        this.convergenceThreshold = builder.convergenceThreshold;
        this.maxIterations = builder.maxIterations;
        this.highRiskThreshold = builder.highRiskThreshold;
        this.externalPenaltyMultiplier = builder.externalPenaltyMultiplier;
    }

    public double getDecayFactor() {
        return decayFactor;
    }

    public double getConvergenceThreshold() {
        return convergenceThreshold;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public int getHighRiskThreshold() {
        return highRiskThreshold;
    }

    public double getExternalPenaltyMultiplier() {
        return externalPenaltyMultiplier;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double decayFactor = 0.6;
        private double convergenceThreshold = 0.01;
        private int maxIterations = 100;
        private int highRiskThreshold = 70;
        private double externalPenaltyMultiplier = 1.1;

        public Builder decayFactor(double decayFactor) {
            this.decayFactor = decayFactor;
            return this;
        }

        public Builder convergenceThreshold(double convergenceThreshold) {
            this.convergenceThreshold = convergenceThreshold;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder highRiskThreshold(int highRiskThreshold) {
            this.highRiskThreshold = highRiskThreshold;
            return this;
        }

        public Builder externalPenaltyMultiplier(double externalPenaltyMultiplier) {
            this.externalPenaltyMultiplier = externalPenaltyMultiplier;
            return this;
        }

        public ScoringConfig build() {
            return new ScoringConfig(this);
        }
    }
}
