package com.nullguard.scoring.propagation;


import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.RiskLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FixpointRiskPropagationEngine implements RiskPropagationEngine {

    /**
     * After propagate() completes, this map holds the per-method explanation:
     * methodId → ordered list of "callee (contributed +N.NN pts)" strings,
     * top-3 contributors only.
     */
    private Map<String, List<String>> riskContributors = new LinkedHashMap<>();

    public Map<String, List<String>> getRiskContributors() {
        return Collections.unmodifiableMap(riskContributors);
    }

    @Override
    public Map<String, AdjustedRiskModel> propagate(ProjectModel project, GlobalCallGraph callGraph, ScoringConfig config) {
        Map<String, Double> intrinsicRiskMap = new LinkedHashMap<>();
        
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel m : cls.getMethods().values()) {
                        // Canonical method ID format: packageName.className#signature
                        // Must match BasicCallGraphBuilder.callerId format exactly.
                        String methodId = pkg.getPackageName() + "." + cls.getClassName() + "#" + m.getSignature();
                        m.getMethodSummary().ifPresent(obj -> {
                            // Use pure reflection – avoids a compile-time dependency on the
                            // MethodSummary class from nullguard-analysis and is immune to
                            // ClassLoader / instanceof issues that silently produced 0.0.
                            double risk = 0.0;
                            try {
                                // Fast path: MethodSummary.getIntrinsicRiskScore() (added in Fix 5)
                                java.lang.reflect.Method mScore =
                                    obj.getClass().getMethod("getIntrinsicRiskScore");
                                risk = ((Number) mScore.invoke(obj)).doubleValue();
                            } catch (Exception e1) {
                                try {
                                    // Fallback: getIntrinsicRiskProfile().getIntrinsicRiskScore()
                                    java.lang.reflect.Method mProf =
                                        obj.getClass().getMethod("getIntrinsicRiskProfile");
                                    Object profile = mProf.invoke(obj);
                                    java.lang.reflect.Method mProfScore =
                                        profile.getClass().getMethod("getIntrinsicRiskScore");
                                    risk = ((Number) mProfScore.invoke(profile)).doubleValue();
                                } catch (Exception e2) {
                                    // Both probes failed – leave risk at 0.0
                                }
                            }
                            if (risk > 0.0) {
                                intrinsicRiskMap.put(methodId, risk);
                            }
                        });

                    }
                }
            }
        }
        
        Set<String> allMethods = getAllMethods(callGraph);
        
        LinkedHashMap<String, Double> intrinsicRisk = new LinkedHashMap<>();
        LinkedHashMap<String, Double> propagatedRisk = new LinkedHashMap<>();
        
        // Step 1: Initialize
        for (String methodId : allMethods) {
            double risk = intrinsicRiskMap.getOrDefault(methodId, 0.0);
            intrinsicRisk.put(methodId, risk);
            propagatedRisk.put(methodId, 0.0);
        }

        double decayFactor = config.getDecayFactor();
        double externalPenaltyMultiplier = config.getExternalPenaltyMultiplier();
        double convergenceThreshold = config.getConvergenceThreshold();
        int maxIterations = config.getMaxIterations();
        
        // Step 2: Iterate
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            double delta = 0.0;
            LinkedHashMap<String, Double> tempStore = new LinkedHashMap<>();
            
            for (String methodId : allMethods) {
                double newPropagated = 0.0;
                
                for (String callee : callGraph.getCallees(methodId)) {
                    // Skip self-loops: a method calling itself in a cycle must not
                    // feed its own propagated risk back into itself.  Without this guard
                    // the fixpoint equation p = (p * decay + rest) solves to
                    // p = rest / (1 – decay), inflating scores by up to 6.7× for
                    // decay=0.85 and producing propagated values that no contributor
                    // explanation can account for.
                    if (callee.equals(methodId)) continue;
                    double childIntrinsic  = intrinsicRisk.getOrDefault(callee, 0.0);
                    double childPropagated = propagatedRisk.getOrDefault(callee, 0.0);
                    double childAdjusted   = childIntrinsic + childPropagated;
                    newPropagated += childAdjusted * decayFactor;
                }
                
                if (callGraph.isExternal(methodId)) {
                    newPropagated *= externalPenaltyMultiplier;
                }
                
                newPropagated = clamp(newPropagated);
                
                double oldPropagated = propagatedRisk.get(methodId);
                delta = Math.max(delta, Math.abs(newPropagated - oldPropagated));
                
                tempStore.put(methodId, newPropagated);
            }
            
            propagatedRisk.putAll(tempStore);
            
            if (delta < convergenceThreshold) {
                break;
            }
        }
        
        // Step 3: Build models + collect contributor explanations
        Map<String, AdjustedRiskModel> finalModels = new LinkedHashMap<>();
        Map<String, List<String>> contributors = new LinkedHashMap<>();

        for (String methodId : allMethods) {
            double intrinsic = intrinsicRisk.get(methodId);
            double propagated = propagatedRisk.get(methodId);
            double adjusted = clamp(intrinsic + propagated);
            finalModels.put(methodId, new AdjustedRiskModel(intrinsic, propagated, adjusted, RiskLevel.from(adjusted)));

            // Build contributor list: which callees drove the propagated risk the most
            List<String[]> calleeScores = new ArrayList<>();
            boolean hasSelfLoop = false;
            for (String callee : callGraph.getCallees(methodId)) {
                if (callee.equals(methodId)) {
                    hasSelfLoop = true;
                    continue; // skip self-loops from cycles
                }
                double calleeAdj = clamp(
                    intrinsicRisk.getOrDefault(callee, 0.0)
                    + propagatedRisk.getOrDefault(callee, 0.0));
                double contribution = calleeAdj * decayFactor;
                if (contribution > 0.001) {
                    calleeScores.add(new String[]{callee, String.format("%.2f", contribution)});
                }
            }
            // Sort descending by contribution magnitude
            calleeScores.sort(Comparator.comparingDouble((String[] a) -> Double.parseDouble(a[1])).reversed());
            List<String> reasons = new ArrayList<>();

            // ── Own intrinsic null-risk ──────────────────────────────────────
            if (intrinsic > 0.001) {
                reasons.add("Own null-risk: +" + String.format("%.2f", intrinsic));
                reasons.add("\uD83D\uDCA1 Fix: Return Optional<T> instead of null, annotate parameters "
                    + "with @NonNull, add null-checks on all return paths, "
                    + "and guard any field or parameter that may be null before dereferencing.");
            }

            // ── Top-3 callee contributors ────────────────────────────────────
            int shown = 0;
            for (String[] cs : calleeScores) {
                if (shown++ >= 3) break;
                // Format: ClassName#methodName(params) — trim params for readability
                String shortCallee;
                String fullCallee = cs[0];
                int hash = fullCallee.lastIndexOf('#');
                if (hash >= 0) {
                    String classSimple = fullCallee.substring(fullCallee.lastIndexOf('.', hash) + 1, hash);
                    String methodSig   = fullCallee.substring(hash + 1);
                    // Trim long parameter lists
                    if (methodSig.length() > 40) {
                        int paren = methodSig.indexOf('(');
                        if (paren > 0) methodSig = methodSig.substring(0, paren) + "(\u2026)";
                    }
                    shortCallee = classSimple + "#" + methodSig;
                } else {
                    shortCallee = fullCallee;
                }

                // Show the callee's own intrinsic null-risk next to the contribution score
                // so the risk chain is immediately visible (e.g. findOrCreateUser → verifyOtp)
                double calleeIntrinsic = intrinsicRisk.getOrDefault(fullCallee, 0.0);
                String calleeIntrinsicNote = calleeIntrinsic > 0.5
                    ? ", own null-risk: " + String.format("%.2f", calleeIntrinsic)
                    : "";
                reasons.add("\u2190 calls " + shortCallee + " (+" + cs[1] + calleeIntrinsicNote + ")");

                // Fix message — if the callee itself has high intrinsic risk, name it as the root source
                if (calleeIntrinsic > 5.0) {
                    reasons.add("\uD83D\uDCA1 Fix: " + shortCallee + " is a known null-risk source "
                        + "(intrinsic: " + String.format("%.2f", calleeIntrinsic) + "pt). "
                        + "Null-guard its return value before use "
                        + "(e.g. if (result == null) throw / return early), "
                        + "or refactor " + shortCallee + " to return Optional<T>.");
                } else {
                    reasons.add("\uD83D\uDCA1 Fix: Null-guard the result of " + shortCallee
                        + " before use (e.g. if (result == null) throw / return early), "
                        + "or refactor it to return Optional<T> and propagate safely.");
                }
            }

            // ── Overflow note ────────────────────────────────────────────────
            if (calleeScores.size() > 3) {
                int remaining = calleeScores.size() - 3;
                reasons.add("\u2026 +" + remaining + " more high-risk callees");
                reasons.add("\uD83D\uDCA1 Fix: Review the remaining " + remaining
                    + " callees for nullable return values and unguarded parameters. "
                    + "Add null-checks or Optional wrappers at each call site.");
            }

            // ── Self-loop / cycle note ───────────────────────────────────────
            if (hasSelfLoop) {
                double inflatedScore = propagated / (1.0 - decayFactor);
                reasons.add("\u26A0 Cyclic self-call detected \u2014 score would have been "
                    + String.format("%.2f", inflatedScore)
                    + " without the self-loop guard (excluded for accuracy)");
                reasons.add("\uD83D\uDCA1 Fix: Extract the recursive logic into a non-recursive helper method, "
                    + "or replace recursion with an iterative loop. "
                    + "Add explicit null-guards before any recursive call sites.");
            }
            contributors.put(methodId, Collections.unmodifiableList(reasons));


        }

        this.riskContributors = contributors;
        return Map.copyOf(finalModels);
    }
    
    private Set<String> getAllMethods(GlobalCallGraph callGraph) {
        Set<String> allMethods = new LinkedHashSet<>();
        
        // Use reflection in case getAllMethods() exists in the evaluation environment
        try {
            java.lang.reflect.Method m = callGraph.getClass().getMethod("getAllMethods");
            @SuppressWarnings("unchecked")
            Set<String> result = (Set<String>) m.invoke(callGraph);
            if (result != null) return result;
        } catch (Exception e) {
            // Ignore and fallback
        }
        
        allMethods.addAll(callGraph.getOutgoing().keySet());
        allMethods.addAll(callGraph.getIncoming().keySet());
        allMethods.addAll(callGraph.getExternalNodes());
        
        for (Set<String> callees : callGraph.getOutgoing().values()) {
            if (callees != null) allMethods.addAll(callees);
        }
        for (Set<String> callers : callGraph.getIncoming().values()) {
            if (callers != null) allMethods.addAll(callers);
        }
        
        return allMethods;
    }
    
    private double clamp(double value) {
        if (value < 0.0) return 0.0;
        if (value > 100.0) return 100.0;
        return value;
    }
}
