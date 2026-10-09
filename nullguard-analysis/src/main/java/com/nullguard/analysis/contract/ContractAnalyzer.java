package com.nullguard.analysis.contract;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.engine.ExpressionNullability;
import com.nullguard.analysis.engine.NullAnalysisModel;
import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.analysis.ir.DereferenceInstruction;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.analysis.summary.MethodSummaryEngine;
import com.nullguard.core.model.*;
import java.util.*;

/** Checks declared contracts and actual caller arguments against converged summaries. */
public class ContractAnalyzer {
    private final List<ContractViolation> violations = new ArrayList<>();
    public ContractAnalyzer(AnalysisConfig config) {}
    public void analyze(ProjectModel project) { analyze(project, Map.of()); }
    public void analyze(ProjectModel project, Map<String, Set<String>> edges) {
        violations.clear();
        Map<String, MethodModel> methods = new TreeMap<>();
        project.getModules().values().forEach(mod -> mod.getPackages().values().forEach(pkg ->
                pkg.getClasses().values().forEach(cls -> cls.getMethods().values().forEach(m -> methods.put(MethodIds.of(pkg, cls, m), m)))));
        Map<String, Set<String>> required = new HashMap<>();
        methods.forEach((id, method) -> {
            Set<String> names = new LinkedHashSet<>();
            method.getParameters().stream().filter(p -> p.nonNull() || p.primitive()).forEach(p -> names.add(p.name()));
            model(method).ifPresent(model -> method.getControlFlowModel().ifPresent(cfg -> {
                for (var inst : new BasicInstructionExtractor().extract(cfg))
                    if (inst instanceof DereferenceInstruction d && model.getInStates().getOrDefault(d.id(), Map.of())
                            .getOrDefault(d.variableName(), NullState.UNKNOWN) != NullState.NON_NULL
                            && method.getParameters().stream().anyMatch(p -> p.name().equals(d.variableName()))) names.add(d.variableName());
            }));
            required.put(id, names);
        });
        methods.forEach((id, method) -> check(id, method, methods, required, edges));
    }
    private void check(String id, MethodModel method, Map<String, MethodModel> methods,
                       Map<String, Set<String>> required, Map<String, Set<String>> edges) {
        List<ContractModel.ContractIssue> issues = new ArrayList<>();
        var summary = method.getMethodSummary().filter(MethodSummary.class::isInstance).map(MethodSummary.class::cast).orElse(null);
        if (summary == null) return;
        if (method.isNonNullReturn() && summary.getReturnNullability() != NullState.NON_NULL)
            issues.add(new ContractModel.ContractIssue("NG003", "Declared non-null return may be null", method.getSourceLocation().map(SourceLocation::startLine).orElse(1)));
        method.getParameters().stream().filter(p -> !p.primitive() && !p.nonNull() && required.get(id).contains(p.name()))
                .forEach(p -> issues.add(new ContractModel.ContractIssue("NG002", "Parameter '" + p.name() + "' is dereferenced without a null guard", method.getSourceLocation().map(SourceLocation::startLine).orElse(1))));
        var nullModel = model(method).orElse(null);
        if (nullModel != null) method.getControlFlowModel().ifPresent(cfg -> {
            var instructions = new BasicInstructionExtractor().extract(cfg);
            var returns = MethodSummaryEngine.callReturns(method, methods);
            for (var node : cfg.getNodes().values()) {
                if (node.getLineNumber() < 1 || node.getType() == com.nullguard.core.cfg.NodeType.ENTRY) continue;
                Node ast;
                try { ast = StaticJavaParser.parseStatement(node.getSourceCode()); }
                catch (RuntimeException statement) {
                    try { ast = StaticJavaParser.parseExpression(node.getSourceCode()); }
                    catch (RuntimeException expression) { continue; }
                }
                Map<String, NullState> state = instructions.stream().filter(i -> i.cfgNodeId().equals(node.getId()))
                        .findFirst().map(i -> nullModel.getInStates().getOrDefault(i.id(), Map.of())).orElse(Map.of());
                for (var call : ast.findAll(MethodCallExpr.class)) {
                    String key = ExpressionNullability.callKey(call);
                    Set<String> candidates = new LinkedHashSet<>();
                    method.getSemanticCallSites().stream().filter(c -> (c.getWrittenName() + "/" + c.getArgumentCount()).equals(key))
                            .flatMap(c -> c.getResolvedTarget().stream()).map(ResolvedCallTarget::qualifiedSignature)
                            .filter(methods::containsKey).forEach(candidates::add);
                    if (candidates.isEmpty()) edges.getOrDefault(id, Set.of()).stream().filter(methods::containsKey)
                            .filter(c -> methods.get(c).getMethodName().equals(call.getNameAsString())
                                    && methods.get(c).getParameters().size() == call.getArguments().size()).forEach(candidates::add);
                    for (String calleeId : candidates) {
                        var callee = methods.get(calleeId);
                        for (int n = 0; n < Math.min(call.getArguments().size(), callee.getParameters().size()); n++) {
                            var parameter = callee.getParameters().get(n);
                            if (required.get(calleeId).contains(parameter.name()) && ExpressionNullability.evaluate(call.getArgument(n), state, returns) != NullState.NON_NULL)
                                issues.add(new ContractModel.ContractIssue("NG004", "Nullable argument passed to '" + parameter.name() + "' of " + calleeId, node.getLineNumber()));
                        }
                    }
                }
            }
        });
        boolean returnViolation = issues.stream().anyMatch(i -> i.ruleId().equals("NG003"));
        boolean parameterViolation = issues.stream().anyMatch(i -> !i.ruleId().equals("NG003"));
        int penalty = (returnViolation ? 10 : 0) + (parameterViolation ? 5 : 0);
        method.setContractModel(new ContractModel(returnViolation, parameterViolation, penalty, issues.stream().distinct().toList()));
        if (!issues.isEmpty()) violations.add(new ContractViolation(id, returnViolation, parameterViolation, penalty));
    }
    private static Optional<NullAnalysisModel> model(MethodModel method) {
        return method.getNullAnalysisModel().filter(NullAnalysisModel.class::isInstance).map(NullAnalysisModel.class::cast);
    }
    public List<ContractViolation> getViolations() { return List.copyOf(violations); }
    public int getViolationCount() { return violations.size(); }
    public record ContractViolation(String methodId, boolean returnViolation, boolean parameterViolation, int penalty) {}
}
