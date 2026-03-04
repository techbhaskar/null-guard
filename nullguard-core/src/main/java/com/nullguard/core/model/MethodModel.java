package com.nullguard.core.model;

import com.nullguard.core.cfg.ControlFlowModel;
import java.util.Objects;
import java.util.Optional;

/**
 * MethodModel – immutable-by-construction method descriptor.
 *
 * <p>Several fields are intentionally non-final so that later analysis passes
 * can inject computed data without needing to rebuild the whole object graph.
 * Only ANALYSIS modules may call these setters — no other module should mutate
 * a MethodModel after the initial parse.
 *
 * <p>Currently mutable fields (set only after parse):
 * <ul>
 *   <li>{@code methodSummary}     – set by MethodSummaryEngine</li>
 *   <li>{@code contractModel}     – set by ContractAnalyzer</li>
 *   <li>{@code adjustedRiskModel} – set by FixpointRiskPropagationEngine</li>
 *   <li>{@code reachData}         – set by ReachTracker</li>
 * </ul>
 */
public final class MethodModel {
    private final String methodName;
    private final String signature;
    private final ControlFlowModel controlFlowModel;

    private final Object nullAnalysisModel;

    /** Set by MethodSummaryEngine after the CFG / null-state pass. */
    private Object methodSummary;
    /** Set by ContractAnalyzer after method summary is available. */
    private Object contractModel;
    /** Set by FixpointRiskPropagationEngine during risk propagation. */
    private Object adjustedRiskModel;
    /** Set by ReachTracker during API flow analysis. */
    private Object reachData;

    private final Object riskModel;
    private final Object suggestions;
    private final Object issues;

    private MethodModel(Builder builder) {
        this.methodName        = Objects.requireNonNull(builder.methodName, "Method name cannot be null");
        this.signature         = Objects.requireNonNull(builder.signature, "Signature cannot be null");
        this.controlFlowModel  = builder.controlFlowModel;
        this.nullAnalysisModel = builder.nullAnalysisModel;
        this.methodSummary     = builder.methodSummary;
        this.riskModel         = builder.riskModel;
        this.contractModel     = builder.contractModel;
        this.suggestions       = builder.suggestions;
        this.issues            = builder.issues;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getMethodName() { return methodName; }
    public String getSignature()  { return signature; }

    public Optional<ControlFlowModel> getControlFlowModel()  { return Optional.ofNullable(controlFlowModel); }
    public Optional<Object>           getNullAnalysisModel()  { return Optional.ofNullable(nullAnalysisModel); }
    public Optional<Object>           getMethodSummary()      { return Optional.ofNullable(methodSummary); }
    public Optional<Object>           getRiskModel()          { return Optional.ofNullable(riskModel); }
    public Optional<Object>           getContractModel()      { return Optional.ofNullable(contractModel); }
    public Optional<Object>           getAdjustedRiskModel()  { return Optional.ofNullable(adjustedRiskModel); }
    public Optional<Object>           getReachData()          { return Optional.ofNullable(reachData); }
    public Optional<Object>           getSuggestions()        { return Optional.ofNullable(suggestions); }
    public Optional<Object>           getIssues()             { return Optional.ofNullable(issues); }

    // ── Post-construction setters (analysis modules only) ─────────────────────

    /** Called by MethodSummaryEngine after the null-state analysis pass. */
    public void setMethodSummary(Object summary)          { this.methodSummary     = summary; }

    /** Called by ContractAnalyzer after analyzing method boundary contracts. */
    public void setContractModel(Object contract)         { this.contractModel     = contract; }

    /** Called by FixpointRiskPropagationEngine after risk propagation. */
    public void setAdjustedRiskModel(Object riskModel)   { this.adjustedRiskModel = riskModel; }

    /** Called by ReachTracker after API flow mapping. */
    public void setReachData(Object reach)                { this.reachData         = reach; }

    // ── Builder ───────────────────────────────────────────────────────────────

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

        public Builder methodName(String methodName)           { this.methodName = methodName; return this; }
        public Builder signature(String signature)             { this.signature = signature; return this; }
        public Builder controlFlowModel(ControlFlowModel cfm)  { this.controlFlowModel = cfm; return this; }
        public Builder nullAnalysisModel(Object m)             { this.nullAnalysisModel = m; return this; }
        public Builder methodSummary(Object s)                 { this.methodSummary = s; return this; }
        public Builder riskModel(Object r)                     { this.riskModel = r; return this; }
        public Builder contractModel(Object c)                 { this.contractModel = c; return this; }
        public Builder suggestions(Object s)                   { this.suggestions = s; return this; }
        public Builder issues(Object i)                        { this.issues = i; return this; }

        public MethodModel build() { return new MethodModel(this); }
    }
}
