package com.nullguard.analysis.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ApiFlowGraph – v1.1 IR model that represents the complete bipartite graph of
 * API endpoints and the method-level call-chains they own.
 *
 * <p>Architecture spec (v1.1):
 * <pre>
 * ProjectModel
 *  ├── GlobalCallGraph
 *  ├── PropagationGraph
 *  ├── ApiFlowGraph        ← this class
 *  └── Modules …
 * </pre>
 *
 * <p>The graph maps each API endpoint ID to its ordered propagation chain
 * (entry → service → repo … → leaf), mirrors of the chain stored in
 * {@link ApiEndpointModel#getPropagationChain()}, but indexed for fast lookup.
 */
public final class ApiFlowGraph {

    /** endpointId → ordered list of method IDs in the propagation chain */
    private final Map<String, List<String>> endpointChains;

    /** methodId → set of endpoint IDs that can reach this method */
    private final Map<String, List<String>> methodToEndpoints;

    private ApiFlowGraph(Builder builder) {
        this.endpointChains   = Collections.unmodifiableMap(new LinkedHashMap<>(builder.endpointChains));
        this.methodToEndpoints = Collections.unmodifiableMap(new LinkedHashMap<>(builder.methodToEndpoints));
    }

    /** Returns the propagation chain for a given API endpoint ID, or empty list if not found. */
    public List<String> getChain(String endpointId) {
        return endpointChains.getOrDefault(endpointId, Collections.emptyList());
    }

    /** Returns all endpoint IDs that reach a given method. */
    public List<String> getEndpointsForMethod(String methodId) {
        return methodToEndpoints.getOrDefault(methodId, Collections.emptyList());
    }

    /** How many API endpoints can reach a given method (used for blast-radius scoring). */
    public int getApiReachCount(String methodId) {
        return methodToEndpoints.getOrDefault(methodId, Collections.emptyList()).size();
    }

    /** All registered endpoint IDs. */
    public java.util.Set<String> getEndpointIds() {
        return endpointChains.keySet();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<String, List<String>> endpointChains    = new LinkedHashMap<>();
        private final Map<String, List<String>> methodToEndpoints = new LinkedHashMap<>();

        /**
         * Register one API endpoint with its propagation chain.
         * Also updates the reverse index (method → endpoints).
         */
        public Builder addEndpoint(String endpointId, List<String> chain) {
            Objects.requireNonNull(endpointId, "endpointId cannot be null");
            List<String> safe = chain == null ? Collections.emptyList() : chain;
            endpointChains.put(endpointId, Collections.unmodifiableList(new ArrayList<>(safe)));
            // Reverse index
            for (String methodId : safe) {
                methodToEndpoints.computeIfAbsent(methodId, k -> new ArrayList<>()).add(endpointId);
            }
            return this;
        }

        public ApiFlowGraph build() { return new ApiFlowGraph(this); }
    }
}
