package com.nullguard.analysis.lattice;
public enum NullState {
    NULL,
    NON_NULL,
    UNKNOWN;

    public NullState merge(NullState other) {
        if (this == UNKNOWN || other == UNKNOWN) return UNKNOWN;
        if (this == other) return this;
        return UNKNOWN;
    }
}
