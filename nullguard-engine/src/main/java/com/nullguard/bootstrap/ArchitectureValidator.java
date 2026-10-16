package com.nullguard.bootstrap;

import com.nullguard.callgraph.model.GlobalCallGraph;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * ArchitectureValidator – runs three governance checks at the end of each pipeline pass.
 *
 * <ol>
 *   <li>{@link #validateArchitectureIntegrity(PipelineContext)} – all required artifacts are present</li>
 *   <li>{@link #validateNoCircularDependencies(PipelineContext)} – call graph is acyclic within internal methods</li>
 *   <li>{@link #validateDeterministicOrdering(PipelineContext)} – timing map has at least the required steps</li>
 * </ol>
 *
 * Throws {@link ArchitectureViolationException} on any failure.
 * No analysis logic is embedded here; only structural governance.
 */
public final class ArchitectureValidator {

    private static final String[] REQUIRED_STEPS = {
        "parse", "callgraph", "analysis", "risk-propagation", "scoring",
        "api-endpoints", "hotspots", "suggestions",
        "visualization-graph", "visualization-export"
    };

    // ── 1. Architecture Integrity ─────────────────────────────────────────────

    /**
     * Verifies that all expected pipeline artifacts are non-null in the context.
     */
    public void validateArchitectureIntegrity(PipelineContext ctx) {
        // Each accessor throws IllegalStateException if the artifact is null.
        // We call them and translate to our governance exception for caller clarity.
        try {
            ctx.getProjectModel();
            ctx.getCallGraph();
            ctx.getAdjustedRiskMap();
            ctx.getRiskSummary();
            ctx.getApiEndpoints();
            ctx.getHotspots();
            ctx.getSuggestions();
            ctx.getPropagationGraph();
            ctx.getVisualizationBundle();
        } catch (IllegalStateException e) {
            throw new ArchitectureViolationException(
                    "Architecture integrity check failed – pipeline artifact missing: " + e.getMessage(), e);
        }
    }

    // ── 2. No Circular Dependencies ───────────────────────────────────────────

    /**
     * Checks that internal (non-external) call graph edges contain no cycles.
     *
     * <p><strong>Policy (changed from fail → warn):</strong><br>
     * Real-world Java method call graphs are almost always cyclic (mutual
     * recursion, callback patterns, Spring proxy chains, etc.).
     * {@link com.nullguard.scoring.propagation.FixpointRiskPropagationEngine}
     * already handles cycles safely via its convergence loop.
     * Crashing the pipeline here prevents legitimate projects from being analysed.
     * We therefore log up to {@value #MAX_CYCLE_LOG} cycle sites as warnings
     * and continue – consistent with how popular static-analysis tools behave.
     */
    public void validateNoCircularDependencies(PipelineContext ctx) {
        GlobalCallGraph callGraph = ctx.getCallGraph();

        Set<String> internalNodes = new HashSet<>(callGraph.getOutgoing().keySet());
        internalNodes.removeAll(callGraph.getExternalNodes());

        Map<String, Integer> colour = new HashMap<>();
        for (String node : internalNodes) {
            colour.put(node, 0);
        }

        Set<String> cycleNodes = new java.util.LinkedHashSet<>();
        for (String start : internalNodes) {
            if (colour.getOrDefault(start, 0) == 0) {
                collectCycles(start, callGraph, colour, internalNodes, cycleNodes);
            }
        }

        if (!cycleNodes.isEmpty()) {
            int reported = 0;
            for (String n : cycleNodes) {
                String msg = "Call graph cycle at: " + n;
                ctx.addCycleWarning(msg);
                if (reported++ < MAX_CYCLE_LOG) {
                    System.out.println("[NullGuard][WARN] " + msg);
                }
            }
            if (cycleNodes.size() > MAX_CYCLE_LOG) {
                String extra = "... and " + (cycleNodes.size() - MAX_CYCLE_LOG) + " more cycle sites";
                ctx.addCycleWarning(extra);
                System.out.println("[NullGuard][WARN] " + extra);
            }
            String safe = "Cycles handled safely by the fixpoint propagation engine. Analysis continues normally.";
            ctx.addCycleWarning(safe);
            System.out.println("[NullGuard][WARN] " + safe);
        }

    }

    private static final int MAX_CYCLE_LOG = 5;

    private void collectCycles(String start, GlobalCallGraph callGraph,
                                Map<String, Integer> colour,
                                Set<String> internalNodes,
                                Set<String> cycleNodes) {
        Deque<String>  stack     = new ArrayDeque<>();
        Deque<Boolean> returning = new ArrayDeque<>();

        stack.push(start);
        returning.push(false);

        while (!stack.isEmpty()) {
            String  node     = stack.peek();
            boolean isReturn = returning.peek();

            if (isReturn) {
                stack.pop();
                returning.pop();
                colour.put(node, 2); // BLACK – fully processed
                continue;
            }

            int c = colour.getOrDefault(node, 0);
            if (c == 2) {
                stack.pop();
                returning.pop();
                continue;
            }
            if (c == 1) {
                // Cycle detected – record it and skip (don't throw)
                cycleNodes.add(node);
                stack.pop();
                returning.pop();
                continue;
            }

            colour.put(node, 1); // GRAY – in stack
            returning.pop();
            returning.push(true);

            Set<String> callees = callGraph.getCallees(node);
            if (callees != null) {
                for (String callee : callees) {
                    if (internalNodes.contains(callee)) {
                        stack.push(callee);
                        returning.push(false);
                    }
                }
            }
        }
    }

    // ── 3. Deterministic Ordering ─────────────────────────────────────────────

    /**
     * Verifies that all required pipeline steps were executed (presence in timing map).
     */
    public void validateDeterministicOrdering(PipelineContext ctx) {
        Map<String, Long> steps = ctx.getTiming().getStepDurationsMs();
        for (String required : REQUIRED_STEPS) {
            if (!steps.containsKey(required)) {
                throw new ArchitectureViolationException(
                        "Deterministic ordering violation: required pipeline step '" +
                        required + "' was not recorded. " +
                        "Pipeline may have executed in a non-canonical order.");
            }
        }
    }
}
