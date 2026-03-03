package com.nullguard.callgraph.model;
import java.util.Objects;
public final class ExternalMethodNode {
    private final String methodId;
    private final ExternalReason reason;
    public ExternalMethodNode(String methodId, ExternalReason reason) {
        this.methodId = Objects.requireNonNull(methodId);
        this.reason = Objects.requireNonNull(reason);
    }
    public String getMethodId() { return methodId; }
    public ExternalReason getReason() { return reason; }
}
