package com.nullguard.scoring.model;

public enum RiskLevel {
    LOW(0, 39),
    MEDIUM(40, 59),
    HIGH(60, 79),
    CRITICAL(80, 100);

    private final int min;
    private final int max;

    RiskLevel(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public static RiskLevel from(double risk) {
        if (risk < 40) return LOW;
        if (risk < 60) return MEDIUM;
        if (risk < 80) return HIGH;
        return CRITICAL;
    }
}
