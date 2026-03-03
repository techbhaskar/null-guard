package com.nullguard.analysis.summary;

import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.risk.RiskModel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MethodSummary {
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
