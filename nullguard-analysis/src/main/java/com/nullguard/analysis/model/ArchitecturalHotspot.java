package com.nullguard.analysis.model;

public class ArchitecturalHotspot {
    private final String methodRef;
    private final double hotspotScore;
    private final String severity;

    public ArchitecturalHotspot(String methodRef, double hotspotScore, String severity) {
        this.methodRef = methodRef;
        this.hotspotScore = hotspotScore;
        this.severity = severity;
    }

    public String getMethodRef() { return methodRef; }
    public double getHotspotScore() { return hotspotScore; }
    public String getSeverity() { return severity; }
}
