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

    private final IntrinsicRiskCalculator riskCalculator;

    public MethodSummaryEngine(AnalysisConfig config) {
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
        java.util.Map<String, MethodModel> methods = new java.util.TreeMap<>();
        project.getModules().values().forEach(mod -> mod.getPackages().values().forEach(pkg ->
                pkg.getClasses().values().forEach(cls -> cls.getMethods().values().forEach(method ->
                        methods.put(com.nullguard.core.model.MethodIds.of(pkg, cls, method), method)))));
        // Start unknown and iterate summaries across resolved calls, including recursive cycles.
        for (int pass = 0; pass < Math.max(2, methods.size() + 1); pass++) {
            boolean changed = false;
            for (MethodModel method : methods.values()) {
                var previous = method.getMethodSummary().filter(MethodSummary.class::isInstance).map(MethodSummary.class::cast).orElse(null);
                attachSummary(method, methods);
                var current = method.getMethodSummary().filter(MethodSummary.class::isInstance).map(MethodSummary.class::cast).orElse(null);
                if (current != null && (previous == null || previous.getReturnNullability() != current.getReturnNullability())) changed = true;
            }
            if (!changed) break;
        }
    }

    private void attachSummary(MethodModel method, java.util.Map<String, MethodModel> methods) {
        // Only process methods that have a CFG attached (built by the fixed parser)
        if (method.getControlFlowModel().isEmpty()) return;

        // If a summary was already attached (e.g. by a prior pass), skip

        try {
            com.nullguard.core.cfg.ControlFlowModel cfg = method.getControlFlowModel().get();

            // Run data-flow analysis
            java.util.Map<String, NullState> parameters = new java.util.LinkedHashMap<>();
            method.getParameters().forEach(p -> parameters.put(p.name(), p.primitive() || p.nonNull() ? NullState.NON_NULL : NullState.UNKNOWN));
            java.util.Map<String, NullState> returns = callReturns(method, methods);
            NullAnalysisModel nullModel = new ForwardDataFlowAnalyzer(new BasicInstructionExtractor(), parameters, returns, method.isPrimitiveReturn()).analyze(cfg);
            method.setNullAnalysisModel(nullModel);

            // Compute intrinsic risk from null model
            RiskModel riskProfile = riskCalculator.calculate(nullModel);

            // Build returnNullability
            NullState returnNull = nullModel.isNullableReturn()
                    ? NullState.NULL : NullState.NON_NULL;

            MethodSummary.Builder builder = MethodSummary.builder()
                    .returnNullability(returnNull)
                    .propagatesNullFromCallee(nullModel.isPropagatesNullFromCallee())
                    .intrinsicRiskProfile(riskProfile);
            parameters.forEach(builder::putParameterNullability);
            MethodSummary summary = builder.build();

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

    public static java.util.Map<String, NullState> callReturns(MethodModel method, java.util.Map<String, MethodModel> methods) {
        java.util.Map<String, NullState> returns = new java.util.HashMap<>();
        for (var call : method.getSemanticCallSites()) {
            var target = call.getResolvedTarget().map(t -> methods.get(t.qualifiedSignature())).orElse(null);
            NullState value = NullState.UNKNOWN;
            if (target != null) value = target.isPrimitiveReturn() ? NullState.NON_NULL : target.isNullableReturn() ? NullState.UNKNOWN
                    : target.getMethodSummary().filter(MethodSummary.class::isInstance).map(MethodSummary.class::cast)
                    .map(MethodSummary::getReturnNullability).orElse(NullState.UNKNOWN);
            returns.merge(call.getWrittenName() + "/" + call.getArgumentCount(), value, NullState::merge);
        }
        return returns;
    }
}
