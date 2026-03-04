package com.nullguard.analysis.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ApiEndpointModel – represents a detected REST API endpoint together with its
 * full downstream propagation chain to leaf callees.
 *
 * <p>Fields added for v1.1 compliance:
 * <ul>
 *   <li>{@code apiRiskScore}      – computed from ApiRiskAggregator</li>
 *   <li>{@code propagationDepth}  – number of hops from controller to leaf</li>
 *   <li>{@code hotspotIndicators} – labels such as "SHARED_UTILITY", "HIGH_FANOUT"</li>
 * </ul>
 */
public class ApiEndpointModel {

    private final String       endpointId;       // canonical method ID
    private final String       httpMethod;       // GET / POST / PUT / DELETE / UNKNOWN
    private final String       path;             // e.g. /api/users/{id}
    private final List<String> propagationChain; // ordered: entry → … → leaf

    // v1.1 fields
    private double       apiRiskScore;       // computed after risk propagation
    private int          propagationDepth;   // chain size − 1
    private List<String> hotspotIndicators;  // e.g. ["SHARED_UTILITY", "HIGH_FANOUT"]

    /** Minimal constructor (backward compat). */
    public ApiEndpointModel(String endpointId) {
        this(endpointId, "UNKNOWN", "", Collections.emptyList());
    }

    public ApiEndpointModel(String endpointId,
                            String httpMethod,
                            String path,
                            List<String> propagationChain) {
        this.endpointId        = Objects.requireNonNull(endpointId);
        this.httpMethod        = httpMethod == null ? "UNKNOWN" : httpMethod;
        this.path              = path == null ? "" : path;
        this.propagationChain  = Collections.unmodifiableList(
                propagationChain == null ? Collections.emptyList() : propagationChain);
        this.propagationDepth  = this.propagationChain.isEmpty() ? 0 : this.propagationChain.size() - 1;
        this.apiRiskScore      = 0.0;
        this.hotspotIndicators = Collections.emptyList();
    }

    // ── v1.0 getters ─────────────────────────────────────────────────────────

    public String       getEndpointId()       { return endpointId; }
    public String       getHttpMethod()        { return httpMethod; }
    public String       getPath()              { return path; }
    public List<String> getPropagationChain()  { return propagationChain; }

    // ── v1.1 getters ─────────────────────────────────────────────────────────

    public double       getApiRiskScore()      { return apiRiskScore; }
    public int          getPropagationDepth()  { return propagationDepth; }
    public List<String> getHotspotIndicators() { return hotspotIndicators; }

    // ── v1.1 setters (populated by ApiEndpointAnalyzer / FixpointEngine) ─────

    public void setApiRiskScore(double score) {
        this.apiRiskScore = score;
    }

    public void setHotspotIndicators(List<String> indicators) {
        this.hotspotIndicators = indicators == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(indicators);
    }
}
