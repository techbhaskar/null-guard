package com.nullguard.analysis.contract;

import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.summary.MethodSummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

                        String methodId = com.nullguard.core.model.MethodIds.of(pkg, cls, method);

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
        // MethodSummary is produced in this same module, so a cast is all that was ever
        // needed. The slot is typed to a core read view, so narrow it explicitly here.
        method.getMethodSummary()
                .filter(MethodSummary.class::isInstance)
                .map(MethodSummary.class::cast)
                .ifPresent(summary -> {
            boolean returnViolation    = detectReturnViolation(summary);
            boolean parameterViolation = detectParameterViolation(summary);

            if (returnViolation || parameterViolation) {
                // Penalty: 10pts per return violation, 5pts per parameter violation
                int penalty = (returnViolation ? 10 : 0) + (parameterViolation ? 5 : 0);
                ContractModel model = new ContractModel(returnViolation, parameterViolation, penalty);
                method.setContractModel(model);
                violations.add(new ContractViolation(methodId, returnViolation, parameterViolation, penalty));
            }
        });
    }

    // ── Typed contract detection ──────────────────────────────────────────────
    // Both methods below were four reflective probes with `catch (Exception ignored) {}`
    // and string comparisons against enum names. MethodSummary is in THIS module — the
    // reflection bought nothing and hid every failure. NullState is compared by identity
    // now, so a renamed constant is a compile error instead of a silently false result.

    /** @return true if the method can return null, or nothing is known about its return */
    private static boolean detectReturnViolation(MethodSummary summary) {
        NullState returnState = summary.getReturnNullability();
        return returnState == NullState.NULL || returnState == NullState.UNKNOWN;
    }

    /**
     * @return true if any parameter is null-capable or unknown. Falls back to the
     *         null-propagation flag when no parameter nullability was recorded, which is
     *         currently always — {@code MethodSummaryEngine} never calls
     *         {@code putParameterNullability}, so this remains a known gap rather than a
     *         working parameter-contract check.
     */
    private static boolean detectParameterViolation(MethodSummary summary) {
        Map<String, NullState> parameters = summary.getParameterNullability();
        if (parameters != null && !parameters.isEmpty()) {
            for (NullState state : parameters.values()) {
                if (state == NullState.NULL || state == NullState.UNKNOWN) {
                    return true;
                }
            }
            return false;
        }
        return summary.isPropagatesNullFromCallee();
    }

    // ── Value object for violation records ────────────────────────────────────

    public record ContractViolation(
            String methodId,
            boolean returnViolation,
            boolean parameterViolation,
            int penalty
    ) {}
}
