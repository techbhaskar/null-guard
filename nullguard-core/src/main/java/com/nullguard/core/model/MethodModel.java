package com.nullguard.core.model;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.spi.MethodAnalysisArtifacts.AdjustedRiskSource;
import com.nullguard.core.spi.MethodAnalysisArtifacts.ContractPenaltySource;
import com.nullguard.core.spi.MethodAnalysisArtifacts.IntrinsicRiskSource;
import com.nullguard.core.spi.MethodAnalysisArtifacts.ReachCountSource;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

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
    private final List<SemanticCallSite> semanticCallSites;

    private Object nullAnalysisModel;
    private final SourceLocation sourceLocation;
    private final List<ParameterModel> parameters;
    private final boolean nonNullReturn;
    private final boolean nullableReturn;
    private final boolean primitiveReturn;

    // ── Analysis artifacts ────────────────────────────────────────────────────
    // These were plain `Object` and were read back by consuming modules through
    // reflection, wrapped in catch blocks that fell back to 0.0. A rename in any
    // producing module silently zeroed every score with no compile error. They are now
    // typed to read views declared in nullguard-core, so the same mistake is a build
    // failure. See MethodAnalysisArtifacts.

    /** Set by MethodSummaryEngine after the CFG / null-state pass. */
    private IntrinsicRiskSource methodSummary;
    /** Set by ContractAnalyzer after method summary is available. */
    private ContractPenaltySource contractModel;
    /** Set by FixpointRiskPropagationEngine during risk propagation. */
    private AdjustedRiskSource adjustedRiskModel;
    /** Set by ReachTracker during API flow analysis. */
    private ReachCountSource reachData;

    private final Object riskModel;
    private final Object suggestions;
    private final Object issues;

    private MethodModel(Builder builder) {
        this.methodName        = Objects.requireNonNull(builder.methodName, "Method name cannot be null");
        this.signature         = Objects.requireNonNull(builder.signature, "Signature cannot be null");
        this.controlFlowModel  = builder.controlFlowModel;
        this.semanticCallSites = List.copyOf(builder.semanticCallSites);
        this.nullAnalysisModel = builder.nullAnalysisModel;
        this.sourceLocation = builder.sourceLocation;
        this.parameters = List.copyOf(builder.parameters);
        this.nonNullReturn = builder.nonNullReturn;
        this.nullableReturn = builder.nullableReturn;
        this.primitiveReturn = builder.primitiveReturn;
        this.methodSummary     = builder.methodSummary;
        this.riskModel         = builder.riskModel;
        this.contractModel     = builder.contractModel;
        this.suggestions       = builder.suggestions;
        this.issues            = builder.issues;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getMethodName() { return methodName; }
    public String getSignature()  { return signature; }
    public Optional<SourceLocation> getSourceLocation() { return Optional.ofNullable(sourceLocation); }
    public List<ParameterModel> getParameters() { return parameters; }
    public boolean isNonNullReturn() { return nonNullReturn; }
    public boolean isNullableReturn() { return nullableReturn; }
    public boolean isPrimitiveReturn() { return primitiveReturn; }
    public void setNullAnalysisModel(Object model) { this.nullAnalysisModel = model; }

    public Optional<ControlFlowModel> getControlFlowModel()  { return Optional.ofNullable(controlFlowModel); }
    public List<SemanticCallSite> getSemanticCallSites()      { return semanticCallSites; }
    public Optional<Object>                getNullAnalysisModel() { return Optional.ofNullable(nullAnalysisModel); }
    public Optional<IntrinsicRiskSource>   getMethodSummary()     { return Optional.ofNullable(methodSummary); }
    public Optional<Object>                getRiskModel()         { return Optional.ofNullable(riskModel); }
    public Optional<ContractPenaltySource> getContractModel()     { return Optional.ofNullable(contractModel); }
    public Optional<AdjustedRiskSource>    getAdjustedRiskModel() { return Optional.ofNullable(adjustedRiskModel); }
    public Optional<ReachCountSource>      getReachData()         { return Optional.ofNullable(reachData); }
    public Optional<Object>                getSuggestions()       { return Optional.ofNullable(suggestions); }
    public Optional<Object>                getIssues()            { return Optional.ofNullable(issues); }

    // ── Post-construction setters (analysis modules only) ─────────────────────

    /** Called by MethodSummaryEngine after the null-state analysis pass. */
    public void setMethodSummary(IntrinsicRiskSource summary)      { this.methodSummary     = summary; }

    /** Called by ContractAnalyzer after analyzing method boundary contracts. */
    public void setContractModel(ContractPenaltySource contract)   { this.contractModel     = contract; }

    /** Called by FixpointRiskPropagationEngine after risk propagation. */
    public void setAdjustedRiskModel(AdjustedRiskSource riskModel) { this.adjustedRiskModel = riskModel; }

    /** Called by ReachTracker after API flow mapping. */
    public void setReachData(ReachCountSource reach)               { this.reachData         = reach; }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String methodName;
        private String signature;
        private ControlFlowModel controlFlowModel;
        private List<SemanticCallSite> semanticCallSites = List.of();
        private Object nullAnalysisModel;
        private SourceLocation sourceLocation;
        private List<ParameterModel> parameters = List.of();
        private boolean nonNullReturn;
        private boolean nullableReturn;
        private boolean primitiveReturn;
        private IntrinsicRiskSource methodSummary;
        private Object riskModel;
        private ContractPenaltySource contractModel;
        private Object suggestions;
        private Object issues;

        public Builder methodName(String methodName)           { this.methodName = methodName; return this; }
        public Builder signature(String signature)             { this.signature = signature; return this; }
        public Builder controlFlowModel(ControlFlowModel cfm)  { this.controlFlowModel = cfm; return this; }
        public Builder semanticCallSites(List<SemanticCallSite> calls) {
            this.semanticCallSites = calls == null ? List.of() : List.copyOf(calls);
            return this;
        }
        public Builder nullAnalysisModel(Object m)             { this.nullAnalysisModel = m; return this; }
        public Builder sourceLocation(SourceLocation location) { this.sourceLocation = location; return this; }
        public Builder parameters(List<ParameterModel> values) { this.parameters = List.copyOf(values); return this; }
        public Builder nonNullReturn(boolean value) { this.nonNullReturn = value; return this; }
        public Builder nullableReturn(boolean value) { this.nullableReturn = value; return this; }
        public Builder primitiveReturn(boolean value) { this.primitiveReturn = value; return this; }
        public Builder methodSummary(IntrinsicRiskSource s)    { this.methodSummary = s; return this; }
        public Builder riskModel(Object r)                     { this.riskModel = r; return this; }
        public Builder contractModel(ContractPenaltySource c)  { this.contractModel = c; return this; }
        public Builder suggestions(Object s)                   { this.suggestions = s; return this; }
        public Builder issues(Object i)                        { this.issues = i; return this; }

        public MethodModel build() { return new MethodModel(this); }
    }
}
