package com.nullguard.analysis.contract;

import com.nullguard.core.spi.MethodAnalysisArtifacts.ContractPenaltySource;

/**
 * Boundary-contract violations found for one method.
 *
 * <p>Implements {@link ContractPenaltySource} so the scoring module can read the penalty through
 * a typed core interface instead of {@code obj.getClass().getMethod("getContractPenalty")}.
 */
public final class ContractModel implements ContractPenaltySource {
    private final boolean returnContractViolation;
    private final boolean parameterContractViolation;
    private final int contractPenalty;
    private final java.util.List<ContractIssue> issues;
    public ContractModel(boolean returnContractViolation, boolean parameterContractViolation, int contractPenalty) {
        this(returnContractViolation, parameterContractViolation, contractPenalty, java.util.List.of());
    }
    public ContractModel(boolean returnContractViolation, boolean parameterContractViolation, int contractPenalty,
                         java.util.List<ContractIssue> issues) {
        this.returnContractViolation = returnContractViolation;
        this.parameterContractViolation = parameterContractViolation;
        this.contractPenalty = contractPenalty;
        this.issues = java.util.List.copyOf(issues);
    }
    public java.util.List<ContractIssue> getIssues() { return issues; }
    public record ContractIssue(String ruleId, String message, int line) {}
    @Override public boolean isReturnContractViolation() { return returnContractViolation; }
    @Override public boolean isParameterContractViolation() { return parameterContractViolation; }
    @Override public int getContractPenalty() { return contractPenalty; }
}
