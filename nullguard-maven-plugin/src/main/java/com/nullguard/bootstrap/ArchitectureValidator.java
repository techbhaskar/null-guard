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
     * Checks that internal (non-external) call graph edges contain no cycles
     * using iterative DFS with three-colour marking.
     */
    public void validateNoCircularDependencies(PipelineContext ctx) {
        GlobalCallGraph callGraph = ctx.getCallGraph();

        // Collect internal nodes only (external nodes are leaves and cannot form cycles)
        Set<String> internalNodes = new HashSet<>(callGraph.getOutgoing().keySet());
        internalNodes.removeAll(callGraph.getExternalNodes());

        // Three-colour DFS: WHITE=0 (unvisited), GRAY=1 (in stack), BLACK=2 (done)
        Map<String, Integer> colour = new HashMap<>();
        for (String node : internalNodes) {
            colour.put(node, 0);
        }

        for (String start : internalNodes) {
            if (colour.getOrDefault(start, 0) == 0) {
                detectCycle(start, callGraph, colour, internalNodes);
            }
        }
    }

    private void detectCycle(String start, GlobalCallGraph callGraph,
                              Map<String, Integer> colour, Set<String> internalNodes) {
        // Iterative DFS to avoid stack overflow on large graphs
        Deque<String> stack = new ArrayDeque<>();
        Deque<Boolean> returning = new ArrayDeque<>();

        stack.push(start);
        returning.push(false);

        while (!stack.isEmpty()) {
            String node = stack.peek();
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
                throw new ArchitectureViolationException(
                        "Circular dependency detected at method: " + node +
                        ". The call graph must be acyclic for deterministic propagation.");
            }

            colour.put(node, 1); // GRAY – in stack
            returning.pop();
            returning.push(true); // mark for post-processing

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
