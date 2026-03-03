package com.nullguard.scoring.model;

import java.util.Objects;

public final class ProjectRiskSummary {
    private final double stabilityIndex;
    private final String grade;
    private final double averageRisk;
    private final double maxRisk;
    private final double highRiskRatio;
    private final int totalMethods;
    private final int highRiskMethods;
    private final double blastRadiusScore;
    private final int totalExternalMethods;

    public ProjectRiskSummary(double stabilityIndex, String grade, double averageRisk, double maxRisk, 
                              double highRiskRatio, int totalMethods, int highRiskMethods, 
                              double blastRadiusScore, int totalExternalMethods) {
        this.stabilityIndex = stabilityIndex;
        this.grade = grade;
        this.averageRisk = averageRisk;
        this.maxRisk = maxRisk;
        this.highRiskRatio = highRiskRatio;
        this.totalMethods = totalMethods;
        this.highRiskMethods = highRiskMethods;
        this.blastRadiusScore = blastRadiusScore;
        this.totalExternalMethods = totalExternalMethods;
    }

    public double getStabilityIndex() {
        return stabilityIndex;
    }

    public String getGrade() {
        return grade;
    }

    public double getAverageRisk() {
        return averageRisk;
    }

    public double getMaxRisk() {
        return maxRisk;
    }

    public double getHighRiskRatio() {
        return highRiskRatio;
    }

    public int getTotalMethods() {
        return totalMethods;
    }

    public int getHighRiskMethods() {
        return highRiskMethods;
    }

    public double getBlastRadiusScore() {
        return blastRadiusScore;
    }

    public int getTotalExternalMethods() {
        return totalExternalMethods;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProjectRiskSummary that = (ProjectRiskSummary) o;
        return Double.compare(that.stabilityIndex, stabilityIndex) == 0 &&
                Double.compare(that.averageRisk, averageRisk) == 0 &&
                Double.compare(that.maxRisk, maxRisk) == 0 &&
                Double.compare(that.highRiskRatio, highRiskRatio) == 0 &&
                totalMethods == that.totalMethods &&
                highRiskMethods == that.highRiskMethods &&
                Double.compare(that.blastRadiusScore, blastRadiusScore) == 0 &&
                totalExternalMethods == that.totalExternalMethods &&
                Objects.equals(grade, that.grade);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stabilityIndex, grade, averageRisk, maxRisk, highRiskRatio, totalMethods, highRiskMethods, blastRadiusScore, totalExternalMethods);
    }
}
