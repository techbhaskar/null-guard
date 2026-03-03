package com.nullguard.core.model;

import com.nullguard.core.cfg.ControlFlowModel;
import java.util.Objects;
import java.util.Optional;

public final class MethodModel {
    private final String methodName;
    private final String signature;
    private final ControlFlowModel controlFlowModel; 
    
    private final Object nullAnalysisModel;
    private final Object methodSummary;
    private final Object riskModel;
    private final Object contractModel;
    private final Object suggestions;
    private final Object issues;

    private MethodModel(Builder builder) {
        this.methodName = Objects.requireNonNull(builder.methodName, "Method name cannot be null");
        this.signature = Objects.requireNonNull(builder.signature, "Signature cannot be null");
        this.controlFlowModel = builder.controlFlowModel;
        this.nullAnalysisModel = builder.nullAnalysisModel;
        this.methodSummary = builder.methodSummary;
        this.riskModel = builder.riskModel;
        this.contractModel = builder.contractModel;
        this.suggestions = builder.suggestions;
        this.issues = builder.issues;
    }

    public String getMethodName() { return methodName; }
    public String getSignature() { return signature; }
    
    public Optional<ControlFlowModel> getControlFlowModel() { return Optional.ofNullable(controlFlowModel); }
    public Optional<Object> getNullAnalysisModel() { return Optional.ofNullable(nullAnalysisModel); }
    public Optional<Object> getMethodSummary() { return Optional.ofNullable(methodSummary); }
    public Optional<Object> getRiskModel() { return Optional.ofNullable(riskModel); }
    public Optional<Object> getContractModel() { return Optional.ofNullable(contractModel); }
    public Optional<Object> getSuggestions() { return Optional.ofNullable(suggestions); }
    public Optional<Object> getIssues() { return Optional.ofNullable(issues); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String methodName;
        private String signature;
        private ControlFlowModel controlFlowModel;
        private Object nullAnalysisModel;
        private Object methodSummary;
        private Object riskModel;
        private Object contractModel;
        private Object suggestions;
        private Object issues;

        public Builder methodName(String methodName) { this.methodName = methodName; return this; }
        public Builder signature(String signature) { this.signature = signature; return this; }
        public Builder controlFlowModel(ControlFlowModel cfm) { this.controlFlowModel = cfm; return this; }
        
        public MethodModel build() { return new MethodModel(this); }
    }
}
