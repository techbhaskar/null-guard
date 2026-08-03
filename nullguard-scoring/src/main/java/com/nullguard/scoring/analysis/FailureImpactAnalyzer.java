package com.nullguard.scoring.analysis;

import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.scoring.model.ImpactChain;
import com.nullguard.callgraph.model.GlobalCallGraph;

import java.util.*;

/**
 * FailureImpactAnalyzer – maps method failures to their entry-point impact maps.
 * 
 * Vision: If method X throws NPE, what is the blast radius?
 */
public final class FailureImpactAnalyzer {

    /**
     * Upper bound on chains enumerated per method, to keep the traversal bounded on dense
     * graphs. This is an analysis limit; it must not be tuned for display purposes, because
     * {@code FixpointRiskPropagationEngine} derives {@code apiExposureWeight} from the number of
     * chains returned here.
     */
    public static final int MAX_CHAINS_PER_METHOD = 50;

    public Map<String, List<ImpactChain>> analyze(GlobalCallGraph callGraph, List<ApiEndpointModel> endpoints) {
        Map<String, List<ImpactChain>> impactMap = new LinkedHashMap<>();
        Set<String> entryPoints = new HashSet<>();
        for (ApiEndpointModel api : endpoints) {
            entryPoints.add(api.getEndpointId());
        }

        // We only care about methods that are actually in the graph
        Set<String> allMethods = new HashSet<>();
        allMethods.addAll(callGraph.getOutgoing().keySet());
        allMethods.addAll(callGraph.getIncoming().keySet());

        for (String methodId : allMethods) {
            List<ImpactChain> chains = findImpactChains(methodId, callGraph, entryPoints);
            if (!chains.isEmpty()) {
                impactMap.put(methodId, chains);
            }
        }

        return impactMap;
    }

    private List<ImpactChain> findImpactChains(String startMethod, GlobalCallGraph callGraph, Set<String> entryPoints) {
        List<ImpactChain> result = new ArrayList<>();
        if (entryPoints.contains(startMethod)) {
            result.add(new ImpactChain(List.of(startMethod), startMethod, "CRITICAL (Entry Point)"));
            return result;
        }

        // BFS Upwards
        Queue<List<String>> queue = new LinkedList<>();
        queue.add(new ArrayList<>(List.of(startMethod)));
        Set<String> visited = new HashSet<>();
        visited.add(startMethod);

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            String current = path.get(path.size() - 1);

            Set<String> callers = callGraph.getCallers(current);
            if (callers == null || callers.isEmpty()) continue;

            for (String caller : callers) {
                if (visited.contains(caller)) continue;

                List<String> nextPath = new ArrayList<>(path);
                nextPath.add(caller);

                if (entryPoints.contains(caller)) {
                    // Found an impact chain to an API entry point
                    result.add(new ImpactChain(nextPath, caller, "CRITICAL"));
                } else {
                    visited.add(caller);
                    // Only continue if we haven't reached a massive depth (limit to 5 for impact maps)
                    if (nextPath.size() < 6) {
                        queue.add(nextPath);
                    }
                }
            }
            
            // Analysis bound, NOT a presentation bound. This used to break at 3 "to avoid UI
            // clutter", but FixpointRiskPropagationEngine feeds impactChains.size() into
            // apiExposureWeight — so a constant chosen for display was silently capping a
            // scoring input, and changing it would have changed every risk score. Renderers must
            // do their own truncation.
            if (result.size() >= MAX_CHAINS_PER_METHOD) break;
        }

        return result;
    }
}
