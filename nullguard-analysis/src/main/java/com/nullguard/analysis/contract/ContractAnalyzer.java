package com.nullguard.analysis.contract;

import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.lattice.NullState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ContractAnalyzer – detects API contract violations across method boundaries.
 *
 * <p>Two violation types (v1.0 &amp; v1.1 spec):
 * <ol>
 *   <li><b>Return-contract violation</b>: a method's MethodSummary declares
 *       {@code returnsNull=true} (i.e., it can return null). Any caller that
 *       does not guard the result has a boundary amplification risk.</li>
 *   <li><b>Parameter-contract violation</b>: a method summary shows that at
 *       least one parameter has a {@code UNKNOWN} or {@code NULL} nullability
 *       state, indicating unchecked nullable inputs.</li>
 * </ol>
 *
 * <p>Each violation attaches a {@link ContractModel} to the violating
 * {@link MethodModel} via {@code MethodModel.setContractModel()}.
 */
public class ContractAnalyzer {

    private final AnalysisConfig config;
    private final List<ContractViolation> violations;

    public ContractAnalyzer(AnalysisConfig config) {
        this.config     = config;
        this.violations = new ArrayList<>();
    }

    /**
     * Analyzes every method in the project and attaches a {@link ContractModel}
     * where a violation is found.
     */
    public void analyze(ProjectModel project) {
        violations.clear();

        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel method : cls.getMethods().values()) {

                        String methodId = pkg.getPackageName() + "."
                                + cls.getClassName() + "#"
                                + method.getSignature();

                        analyzeMethod(methodId, method);
                    }
                }
            }
        }
    }

    public List<ContractViolation> getViolations() {
        return Collections.unmodifiableList(violations);
    }

    public int getViolationCount() {
        return violations.size();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void analyzeMethod(String methodId, MethodModel method) {
        method.getMethodSummary().ifPresent(summaryObj -> {
            boolean returnViolation    = detectReturnViolation(summaryObj);
            boolean parameterViolation = detectParameterViolation(summaryObj);

            if (returnViolation || parameterViolation) {
                // Penalty: 10pts per return violation, 5pts per parameter violation
                int penalty = (returnViolation ? 10 : 0) + (parameterViolation ? 5 : 0);
                ContractModel model = new ContractModel(returnViolation, parameterViolation, penalty);
                method.setContractModel(model);
                violations.add(new ContractViolation(methodId, returnViolation, parameterViolation, penalty));
            }
        });
    }

    /**
     * Returns true if the method summary indicates the method CAN return null
     * (i.e., returnNullability == NULL or the method has a nullable return flag).
     */
    private static boolean detectReturnViolation(Object summaryObj) {
        try {
            // Try getReturnNullability() → NullState enum
            Object nullState = summaryObj.getClass()
                    .getMethod("getReturnNullability")
                    .invoke(summaryObj);
            if (nullState != null) {
                String stateName = nullState.toString();
                return "NULL".equals(stateName) || "UNKNOWN".equals(stateName);
            }
        } catch (Exception ignored) {}

        // Fallback: check isNullableReturn() if present
        try {
            Object result = summaryObj.getClass()
                    .getMethod("isNullableReturn")
                    .invoke(summaryObj);
            return Boolean.TRUE.equals(result);
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Returns true if any parameter of the method has a NULL or UNKNOWN
     * nullability state, indicating the method may accept nullable inputs
     * without guarding them.
     */
    private static boolean detectParameterViolation(Object summaryObj) {
        try {
            // getParameterNullability() → Map<String, NullState>
            Object paramMap = summaryObj.getClass()
                    .getMethod("getParameterNullability")
                    .invoke(summaryObj);
            if (paramMap instanceof java.util.Map<?, ?> map) {
                for (Object state : map.values()) {
                    String stateName = state.toString();
                    if ("NULL".equals(stateName) || "UNKNOWN".equals(stateName)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Fallback: isPropagatesNullFromCallee()
        try {
            Object result = summaryObj.getClass()
                    .getMethod("isPropagatesNullFromCallee")
                    .invoke(summaryObj);
            return Boolean.TRUE.equals(result);
        } catch (Exception ignored) {}

        return false;
    }

    // ── Value object for violation records ────────────────────────────────────

    public record ContractViolation(
            String methodId,
            boolean returnViolation,
            boolean parameterViolation,
            int penalty
    ) {}
}
