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
    public ContractModel(boolean returnContractViolation, boolean parameterContractViolation, int contractPenalty) {
        this.returnContractViolation = returnContractViolation;
        this.parameterContractViolation = parameterContractViolation;
        this.contractPenalty = contractPenalty;
    }
    @Override public boolean isReturnContractViolation() { return returnContractViolation; }
    @Override public boolean isParameterContractViolation() { return parameterContractViolation; }
    @Override public int getContractPenalty() { return contractPenalty; }
}
