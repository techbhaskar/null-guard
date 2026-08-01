package com.nullguard.analysis.summary;

import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.engine.ForwardDataFlowAnalyzer;
import com.nullguard.analysis.engine.NullAnalysisModel;
import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.risk.IntrinsicRiskCalculator;
import com.nullguard.analysis.risk.RiskModel;

/**
 * MethodSummaryEngine – was a no-op stub.
 *
 * <h3>Now (Fix 4)</h3>
 * For every method in the project that has a CFG attached:
 * <ol>
 *   <li>Run {@link ForwardDataFlowAnalyzer} to get the null-state model</li>
 *   <li>Compute intrinsic risk via {@link IntrinsicRiskCalculator}</li>
 *   <li>Build a {@link MethodSummary} and attach it to the {@link MethodModel}
 *       via the existing {@code methodSummary} object slot</li>
 * </ol>
 *
 * <p>Without this step, {@code FixpointRiskPropagationEngine} finds
 * {@code methodSummary = Optional.empty()} for every method and skips them all,
 * leaving every intrinsic risk score at 0.0.
 */
public class MethodSummaryEngine {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(MethodSummaryEngine.class);

    private final AnalysisConfig config;
    private final ForwardDataFlowAnalyzer dataFlowAnalyzer;
    private final IntrinsicRiskCalculator riskCalculator;

    public MethodSummaryEngine(AnalysisConfig config) {
        this.config = config;
        this.dataFlowAnalyzer = new ForwardDataFlowAnalyzer(new BasicInstructionExtractor());
        this.riskCalculator   = new IntrinsicRiskCalculator();
    }

    /**
     * Enriches every method in the project with a {@link MethodSummary}
     * captured in the {@code methodSummary} object slot of {@link MethodModel}.
     *
     * <p>This calls the public {@link MethodModel#setMethodSummary(Object)} setter directly.
     * There is no registry, no thread-local and no reflection involved — earlier revisions of
     * this Javadoc described all three, none of which ever existed in the code.
     */
    public void run(ProjectModel project) {
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel method : cls.getMethods().values()) {
                        attachSummary(mod, pkg, method);
                    }
                }
            }
        }
    }

    private void attachSummary(ModuleModel mod, PackageModel pkg, MethodModel method) {
        // Only process methods that have a CFG attached (built by the fixed parser)
        if (method.getControlFlowModel().isEmpty()) return;

        // If a summary was already attached (e.g. by a prior pass), skip
        if (method.getMethodSummary().isPresent()) return;

        try {
            com.nullguard.core.cfg.ControlFlowModel cfg = method.getControlFlowModel().get();

            // Run data-flow analysis
            NullAnalysisModel nullModel = dataFlowAnalyzer.analyze(cfg);

            // Compute intrinsic risk from null model
            RiskModel riskProfile = riskCalculator.calculate(nullModel);

            // Build returnNullability
            NullState returnNull = nullModel.isNullableReturn()
                    ? NullState.NULL : NullState.NON_NULL;

            MethodSummary summary = MethodSummary.builder()
                    .returnNullability(returnNull)
                    .propagatesNullFromCallee(nullModel.isPropagatesNullFromCallee())
                    .intrinsicRiskProfile(riskProfile)
                    .build();

            // Use the package-private setter — same package (com.nullguard.core.model)
            // avoids reflection on a final field which silently fails in Java 17+
            method.setMethodSummary(summary);

        } catch (Exception e) {
            // Non-fatal, but never silent: a method with no summary contributes 0.0 risk, so
            // widespread silent failures here present as a clean project.
            LOG.warn("Null analysis failed for {}; this method will contribute no risk.",
                     method.getSignature(), e);
        }
    }
}
