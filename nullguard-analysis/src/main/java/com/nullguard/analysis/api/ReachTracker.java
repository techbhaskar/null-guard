package com.nullguard.analysis.api;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.model.APIFlowTrace;
import com.nullguard.analysis.model.ReachData;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Records, for every method, which API entry points can reach it.
 *
 * <h3>Two bugs this fixes</h3>
 * <ol>
 *   <li><b>The map was inverted.</b> {@code track} keyed {@code reachMap} by the entry point and
 *       stored the methods reachable <em>from</em> it. {@code HotspotDetector} wants the opposite
 *       — how many entry points reach a given method — so even if the data had been delivered it
 *       would have answered the wrong question.</li>
 *   <li><b>Nothing was ever delivered.</b> {@code MethodModel.setReachData} had no callers
 *       anywhere in the repository, so {@code HotspotDetector.extractApiReachCount} always
 *       returned 0, {@code isHotspotCandidate} always failed for any threshold ≥ 1, and
 *       {@code getArchitecturalHotspots()} always returned an empty list. Meanwhile
 *       {@code getReachMap()} had no callers either — the tracker faithfully computed a result
 *       that nothing consumed.</li>
 * </ol>
 *
 * <p>{@link #applyTo(ProjectModel)} now writes the per-method counts onto the model, which is
 * what makes hotspot detection produce output at all.
 *
 * <p>Not thread-safe; accumulates across {@code track} calls until {@link #reset()}.
 */
public class ReachTracker {

    private final AnalysisConfig config;

    /** entry-point method id → methods reachable from it. */
    private final Map<String, ReachData> reachMap;

    /** method id → distinct entry points that reach it. The direction hotspots need. */
    private final Map<String, Set<String>> reachedBy;

    public ReachTracker(AnalysisConfig config) {
        this.config = config;
        this.reachMap = new LinkedHashMap<>();
        this.reachedBy = new LinkedHashMap<>();
    }

    /** Clears all accumulated state so the tracker can be reused across runs. */
    public void reset() {
        reachMap.clear();
        reachedBy.clear();
    }

    /**
     * Records one inter-method flow trace, keyed by the entry-point method id, and credits every
     * downstream method with being reachable from that entry point.
     */
    public void track(APIFlowTrace trace) {
        List<String> path = trace.getPath();
        if (path.isEmpty()) return;

        String entryPoint = path.get(0);
        List<String> reachable = path.size() > 1
                ? new ArrayList<>(path.subList(1, path.size()))
                : List.of();

        ReachData existing = reachMap.get(entryPoint);
        if (existing == null) {
            reachMap.put(entryPoint, new ReachData(reachable.size(), reachable, true));
        } else {
            // Merge: keep the longer reach set
            List<String> merged = existing.getReachableApis().size() >= reachable.size()
                    ? existing.getReachableApis()
                    : reachable;
            reachMap.put(entryPoint, new ReachData(merged.size(), merged, true));
        }

        // Reverse index. The entry point reaches itself, so it counts too.
        reachedBy.computeIfAbsent(entryPoint, k -> new LinkedHashSet<>()).add(entryPoint);
        for (String downstream : reachable) {
            reachedBy.computeIfAbsent(downstream, k -> new LinkedHashSet<>()).add(entryPoint);
        }
    }

    /**
     * Writes the accumulated reach counts onto every matching {@link MethodModel}.
     *
     * <p>Without this step {@code HotspotDetector} can never identify a hotspot, because the
     * reach count it reads is the sole input to {@code isHotspotCandidate}.
     *
     * @return number of methods that received reach data
     */
    public int applyTo(ProjectModel project) {
        int applied = 0;
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel method : cls.getMethods().values()) {
                        String methodId = MethodIds.of(pkg, cls, method);
                        Set<String> entryPoints = reachedBy.get(methodId);
                        if (entryPoints == null || entryPoints.isEmpty()) continue;

                        method.setReachData(new ReachData(
                                entryPoints.size(),
                                new ArrayList<>(entryPoints),
                                true));
                        applied++;
                    }
                }
            }
        }
        return applied;
    }

    /** @return entry point → methods reachable from it (defensive copy) */
    public Map<String, ReachData> getReachMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(reachMap));
    }

    /** @return method id → entry points that reach it (defensive copy) */
    public Map<String, Set<String>> getReachedBy() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        reachedBy.forEach((k, v) -> copy.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return Collections.unmodifiableMap(copy);
    }
}
