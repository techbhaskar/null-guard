package com.nullguard.scoring.config;

import com.nullguard.core.risk.RiskLevel;

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
        /**
         * Default aligned with {@link com.nullguard.core.risk.RiskLevel#HIGH}, which starts
         * at 60. These were 70 and 60 respectively, so a method scoring 65 was rendered as HIGH
         * and coloured orange in the graph while being excluded from {@code highRiskMethods}
         * and {@code highRiskRatio} — two live, contradictory definitions of "high risk".
         */
        private int highRiskThreshold = RiskLevel.HIGH.getMin();
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

        /**
         * Validates before constructing. The builder previously accepted anything, and three
         * settings were quietly catastrophic:
         * <ul>
         *   <li>{@code decayFactor == 1.0} divided by zero in the propagation engine's
         *       explanation text and rendered {@code Infinity} into user-facing output;</li>
         *   <li>{@code decayFactor >= 1.0} made propagation diverge so every method scored 100;</li>
         *   <li>{@code maxIterations <= 0} skipped the fixpoint loop entirely, silently
         *       reporting zero propagated risk for the whole project.</li>
         * </ul>
         *
         * @throws IllegalArgumentException if any value is outside its supportable range
         */
        public ScoringConfig build() {
            if (!(decayFactor >= 0.0) || decayFactor >= 1.0) {
                throw new IllegalArgumentException(
                        "decayFactor must be in [0, 1) - propagation diverges at 1.0 and above; got " + decayFactor);
            }
            if (!(convergenceThreshold > 0.0)) {
                throw new IllegalArgumentException(
                        "convergenceThreshold must be > 0; got " + convergenceThreshold);
            }
            if (maxIterations <= 0) {
                throw new IllegalArgumentException(
                        "maxIterations must be > 0, otherwise no propagation is computed at all; got " + maxIterations);
            }
            if (!(externalPenaltyMultiplier >= 0.0)) {
                throw new IllegalArgumentException(
                        "externalPenaltyMultiplier must be >= 0; got " + externalPenaltyMultiplier);
            }
            if (highRiskThreshold < 0 || highRiskThreshold > 100) {
                throw new IllegalArgumentException(
                        "highRiskThreshold must be within [0, 100]; got " + highRiskThreshold);
            }
            return new ScoringConfig(this);
        }
    }
}
