package com.nullguard.bootstrap;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.scoring.config.ScoringConfig;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Unified NullGuard configuration loaded once and passed into all modules
 * via constructor injection. No module reads configuration statically.
 */
public final class NullGuardConfig {

    // Analysis parameters
    private final int propagationDepthLimit;
    private final double propagationDecayExponent;
    private final double apiRiskWeights;
    private final double hotspotRiskThreshold;
    private final int hotspotReachThreshold;

    // Scoring parameters
    private final double scoringDecayFactor;
    private final double externalPenaltyMultiplier;
    private final double convergenceThreshold;
    private final int maxScoringIterations;
    private final int highRiskThreshold;

    // Policy parameters
    private final boolean failBuild;
    private final String failThreshold;    // e.g. "CRITICAL", "HIGH", "MODERATE"

    // Output parameters
    private final Path outputDirectory;

    private NullGuardConfig(Builder builder) {
        this.propagationDepthLimit   = builder.propagationDepthLimit;
        this.propagationDecayExponent = builder.propagationDecayExponent;
        this.apiRiskWeights          = builder.apiRiskWeights;
        this.hotspotRiskThreshold    = builder.hotspotRiskThreshold;
        this.hotspotReachThreshold   = builder.hotspotReachThreshold;
        this.scoringDecayFactor      = builder.scoringDecayFactor;
        this.externalPenaltyMultiplier = builder.externalPenaltyMultiplier;
        this.convergenceThreshold    = builder.convergenceThreshold;
        this.maxScoringIterations    = builder.maxScoringIterations;
        this.highRiskThreshold       = builder.highRiskThreshold;
        this.failBuild               = builder.failBuild;
        this.failThreshold           = Objects.requireNonNull(builder.failThreshold);
        this.outputDirectory         = builder.outputDirectory;
    }

    // ── Analysis config projection ──────────────────────────────────────────

    public AnalysisConfig toAnalysisConfig() {
        return new AnalysisConfig(
                propagationDepthLimit,
                propagationDecayExponent,
                apiRiskWeights,
                hotspotRiskThreshold,
                hotspotReachThreshold
        );
    }

    // ── Scoring config projection ───────────────────────────────────────────

    public ScoringConfig toScoringConfig() {
        return ScoringConfig.builder()
                .decayFactor(scoringDecayFactor)
                .externalPenaltyMultiplier(externalPenaltyMultiplier)
                .convergenceThreshold(convergenceThreshold)
                .maxIterations(maxScoringIterations)
                .highRiskThreshold(highRiskThreshold)
                .build();
    }

    // ── Getters ─────────────────────────────────────────────────────────────

    public boolean isFailBuild()       { return failBuild; }
    public String getFailThreshold()   { return failThreshold; }
    public Path getOutputDirectory()   { return outputDirectory; }

    // ── Builder ─────────────────────────────────────────────────────────────

    public static Builder defaults() {
        return new Builder();
    }

    public static final class Builder {
        // Analysis defaults
        private int    propagationDepthLimit   = 10;
        private double propagationDecayExponent = 2.0;
        private double apiRiskWeights          = 0.3;
        private double hotspotRiskThreshold    = 0.7;
        private int    hotspotReachThreshold   = 5;

        // Scoring defaults
        private double scoringDecayFactor        = 0.85;
        private double externalPenaltyMultiplier = 1.2;
        private double convergenceThreshold      = 0.001;
        private int    maxScoringIterations      = 100;
        private int    highRiskThreshold         = 70;

        // Policy defaults
        private boolean failBuild      = false;
        private String  failThreshold  = "CRITICAL";

        // Output
        private Path outputDirectory = null;

        public Builder propagationDepthLimit(int v)    { this.propagationDepthLimit   = v; return this; }
        public Builder propagationDecayExponent(double v){ this.propagationDecayExponent = v; return this; }
        public Builder apiRiskWeights(double v)        { this.apiRiskWeights          = v; return this; }
        public Builder hotspotRiskThreshold(double v)  { this.hotspotRiskThreshold    = v; return this; }
        public Builder hotspotReachThreshold(int v)    { this.hotspotReachThreshold   = v; return this; }
        public Builder scoringDecayFactor(double v)    { this.scoringDecayFactor      = v; return this; }
        public Builder externalPenaltyMultiplier(double v){ this.externalPenaltyMultiplier = v; return this; }
        public Builder convergenceThreshold(double v)  { this.convergenceThreshold    = v; return this; }
        public Builder maxScoringIterations(int v)     { this.maxScoringIterations    = v; return this; }
        public Builder highRiskThreshold(int v)        { this.highRiskThreshold       = v; return this; }
        public Builder failBuild(boolean v)            { this.failBuild               = v; return this; }
        public Builder failThreshold(String v)         { this.failThreshold           = v; return this; }
        public Builder outputDirectory(Path v)         { this.outputDirectory         = v; return this; }

        public NullGuardConfig build() { return new NullGuardConfig(this); }
    }
}
