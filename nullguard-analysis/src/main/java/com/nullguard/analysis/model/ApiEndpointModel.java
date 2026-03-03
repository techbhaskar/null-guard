package com.nullguard.analysis.model;

public class ApiEndpointModel {
    private final String endpointId;

    public ApiEndpointModel(String endpointId) {
        this.endpointId = endpointId;
    }

    public String getEndpointId() { return endpointId; }
}
