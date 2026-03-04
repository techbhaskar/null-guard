package com.nullguard.analysis.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a detected API endpoint (REST controller method or similar entry point)
 * together with its full downstream propagation chain all the way to leaf callees.
 */
public class ApiEndpointModel {

    private final String       endpointId;      // canonical method ID
    private final String       httpMethod;      // GET / POST / PUT / DELETE / UNKNOWN
    private final String       path;            // e.g. /api/users/{id}
    private final List<String> propagationChain; // ordered: entry → ... → leaf

    /** Minimal constructor (backward compat). */
    public ApiEndpointModel(String endpointId) {
        this(endpointId, "UNKNOWN", "", Collections.emptyList());
    }

    public ApiEndpointModel(String endpointId,
                            String httpMethod,
                            String path,
                            List<String> propagationChain) {
        this.endpointId       = Objects.requireNonNull(endpointId);
        this.httpMethod       = httpMethod == null ? "UNKNOWN" : httpMethod;
        this.path             = path == null ? "" : path;
        this.propagationChain = Collections.unmodifiableList(
                propagationChain == null ? Collections.emptyList() : propagationChain);
    }

    public String       getEndpointId()       { return endpointId; }
    public String       getHttpMethod()        { return httpMethod; }
    public String       getPath()              { return path; }
    public List<String> getPropagationChain()  { return propagationChain; }
}
