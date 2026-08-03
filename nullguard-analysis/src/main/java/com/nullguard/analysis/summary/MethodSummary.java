package com.nullguard.analysis.summary;

import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.risk.RiskModel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-method result of the null-state analysis.
 *
 * <p>Implements {@link com.nullguard.core.spi.MethodAnalysisArtifacts.IntrinsicRiskSource} so the
 * scoring module reads the intrinsic score through a typed core interface. Previously the scoring
 * engine probed {@code getIntrinsicRiskScore} and then
 * {@code getIntrinsicRiskProfile().getIntrinsicRiskScore} reflectively and fell back to
 * {@code 0.0} inside a swallowed catch, so renaming either accessor zeroed every score in the
 * product with no compile error.
 */
public final class MethodSummary
        implements com.nullguard.core.spi.MethodAnalysisArtifacts.IntrinsicRiskSource {
    private final NullState returnNullability;
    private final Map<String, NullState> parameterNullability;
    private final boolean propagatesNullFromCallee;
    private final RiskModel intrinsicRiskProfile;

    private MethodSummary(Builder builder) {
        this.returnNullability = Objects.requireNonNull(builder.returnNullability);
        this.parameterNullability = Collections.unmodifiableMap(new LinkedHashMap<>(builder.parameterNullability));
        this.propagatesNullFromCallee = builder.propagatesNullFromCallee;
        this.intrinsicRiskProfile = Objects.requireNonNull(builder.intrinsicRiskProfile);
    }

    public NullState getReturnNullability() { return returnNullability; }
    public Map<String, NullState> getParameterNullability() { return parameterNullability; }
    public boolean isPropagatesNullFromCallee() { return propagatesNullFromCallee; }
    public RiskModel getIntrinsicRiskProfile() { return intrinsicRiskProfile; }
    @Override
    public int getIntrinsicRiskScore() { return intrinsicRiskProfile.getIntrinsicRiskScore(); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private NullState returnNullability = NullState.UNKNOWN;
        private final Map<String, NullState> parameterNullability = new LinkedHashMap<>();
        private boolean propagatesNullFromCallee;
        private RiskModel intrinsicRiskProfile;

        public Builder returnNullability(NullState returnNullability) { this.returnNullability = returnNullability; return this; }
        public Builder putParameterNullability(String param, NullState state) { this.parameterNullability.put(param, state); return this; }
        public Builder propagatesNullFromCallee(boolean propagates) { this.propagatesNullFromCallee = propagates; return this; }
        public Builder intrinsicRiskProfile(RiskModel profile) { this.intrinsicRiskProfile = profile; return this; }
        
        public MethodSummary build() { return new MethodSummary(this); }
    }
}
