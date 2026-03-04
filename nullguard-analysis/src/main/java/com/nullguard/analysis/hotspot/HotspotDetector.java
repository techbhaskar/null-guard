package com.nullguard.analysis.hotspot;

import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.analysis.model.ReachData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HotspotDetector – identifies architectural hotspots where a method:
 *  (a) has high propagated risk, AND
 *  (b) is reachable from many API entry points.
 *
 * <p>Triggered condition (v1.1 spec):
 * <pre>
 *   methodUsedByAPIs > threshold  AND  propagatedRisk > threshold
 * </pre>
 *
 * <p>Hotspot score formula:  score = adjustedRisk × log(1 + apiReachCount)
 */
public class HotspotDetector {

    private final AnalysisConfig config;
    private final List<ArchitecturalHotspot> hotspots;

    public HotspotDetector(AnalysisConfig config) {
        this.config   = config;
        this.hotspots = new ArrayList<>();
    }

    /**
     * Evaluates every method in the project and populates the hotspot list.
     * Called after risk propagation so that {@link ReachData} is available.
     */
    public void finalize(ProjectModel project) {
        hotspots.clear();

        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel method : cls.getMethods().values()) {

                        String methodId = pkg.getPackageName() + "."
                                + cls.getClassName() + "#"
                                + method.getSignature();

                        // Gather adjusted risk from stored AdjustedRiskModel (via reflection)
                        double adjustedRisk = extractAdjustedRisk(method);

                        // Gather API reach count from ReachData attached to the method
                        int apiReachCount = extractApiReachCount(method);

                        if (isHotspotCandidate(adjustedRisk, apiReachCount)) {
                            double score    = computeHotspotScore(adjustedRisk, apiReachCount);
                            String severity = determineSeverity(score);
                            hotspots.add(new ArchitecturalHotspot(methodId, score, severity));
                        }
                    }
                }
            }
        }

        // Sort hotspots descending by score for deterministic output
        hotspots.sort((a, b) -> Double.compare(b.getHotspotScore(), a.getHotspotScore()));
    }

    public List<ArchitecturalHotspot> getArchitecturalHotspots() {
        return Collections.unmodifiableList(hotspots);
    }

    // ── Candidate & scoring helpers ───────────────────────────────────────────

    public boolean isHotspotCandidate(double adjustedRisk, int apiReachCount) {
        return adjustedRisk    >= config.getHotspotRiskThreshold()
            && apiReachCount   >= config.getHotspotReachThreshold();
    }

    /** score = adjustedRisk × ln(1 + apiReachCount) */
    public double computeHotspotScore(double adjustedRisk, int apiReachCount) {
        return adjustedRisk * Math.log1p(apiReachCount);
    }

    /**
     * Blended global stability: 70% method-level + 30% API-level.
     * Called from the report assembler when both values are available.
     */
    public double computeGlobalStability(double methodStability, double apiStability) {
        return (methodStability * 0.70) + (apiStability * 0.30);
    }

    public String determineSeverity(double hotspotScore) {
        if (hotspotScore >= 80.0) return "CRITICAL";
        if (hotspotScore >= 60.0) return "HIGH";
        if (hotspotScore >= 40.0) return "MODERATE";
        return "LOW";
    }

    // ── Reflection helpers (cross-module without compile-time dependency) ─────

    private static double extractAdjustedRisk(MethodModel method) {
        // AdjustedRiskModel is stored as an Object to avoid circular module deps —
        // use reflection to read getAdjustedRisk()
        return method.getAdjustedRiskModel()
                .map(obj -> {
                    try {
                        return ((Number) obj.getClass()
                                .getMethod("getAdjustedRisk")
                                .invoke(obj)).doubleValue();
                    } catch (Exception e) {
                        return 0.0;
                    }
                })
                .orElse(0.0);
    }

    private static int extractApiReachCount(MethodModel method) {
        return method.getReachData()
                .map(obj -> {
                    try {
                        return ((Number) obj.getClass()
                                .getMethod("getCount")
                                .invoke(obj)).intValue();
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .orElse(0);
    }
}
