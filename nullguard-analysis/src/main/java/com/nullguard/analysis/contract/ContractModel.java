package com.nullguard.analysis.contract;
public final class ContractModel {
    private final boolean returnContractViolation;
    private final boolean parameterContractViolation;
    private final int contractPenalty;
    public ContractModel(boolean returnContractViolation, boolean parameterContractViolation, int contractPenalty) {
        this.returnContractViolation = returnContractViolation;
        this.parameterContractViolation = parameterContractViolation;
        this.contractPenalty = contractPenalty;
    }
    public boolean isReturnContractViolation() { return returnContractViolation; }
    public boolean isParameterContractViolation() { return parameterContractViolation; }
    public int getContractPenalty() { return contractPenalty; }
}
