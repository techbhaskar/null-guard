package com.nullguard.analysis.summary;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.nullguard.core.model.*;
import java.util.*;

/** Small proven normal-completion contracts; no custom-name or annotation guessing. */
public final class ValidationEffects {
    private ValidationEffects() {}
    public static Set<Integer> infer(MethodModel method) {
        return infer(method, Map.of());
    }
    public static Set<Integer> infer(MethodModel method, Map<String, MethodModel> methods) {
        Set<Integer> result = new LinkedHashSet<>();
        if (method.getBodySource().isEmpty()) return result;
        BlockStmt body;
        try { body = method.getMethodName().equals("<init>")
                ? com.github.javaparser.StaticJavaParser.parseBodyDeclaration("Tmp() " + method.getBodySource()).asConstructorDeclaration().getBody()
                : com.github.javaparser.StaticJavaParser.parseBlock(method.getBodySource()); }
        catch (RuntimeException unsupported) { return result; }
        if (!body.findAll(TryStmt.class).isEmpty()) return result;
        var callEffects = calls(method, methods);
        for (int i = 0; i < method.getParameters().size(); i++) {
            String name = method.getParameters().get(i).name();
            if (body.findAll(AssignExpr.class).stream().anyMatch(a -> a.getTarget().toString().equals(name))) continue;
            for (Statement statement : body.getStatements()) {
                if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression() instanceof MethodCallExpr call)
                    for (int index : callEffects.getOrDefault(call.toString(), Set.of()))
                        if (index < call.getArguments().size() && call.getArgument(index).toString().equals(name)) result.add(i);
                if (statement instanceof IfStmt guard && guard.getCondition() instanceof BinaryExpr condition
                        && condition.getOperator() == BinaryExpr.Operator.EQUALS
                        && ((condition.getLeft().toString().equals(name) && condition.getRight().isNullLiteralExpr())
                         || (condition.getRight().toString().equals(name) && condition.getLeft().isNullLiteralExpr()))) {
                    Statement then = guard.getThenStmt();
                    if (then.isThrowStmt() || (then.isBlockStmt() && then.asBlockStmt().getStatements().size() == 1
                            && then.asBlockStmt().getStatement(0).isThrowStmt())) result.add(i);
                }
                if (statement.isExpressionStmt() && statement.asExpressionStmt().getExpression() instanceof MethodCallExpr call
                        && call.getNameAsString().equals("requireNonNull") && !call.getArguments().isEmpty()
                        && call.getArgument(0).toString().equals(name)
                        && method.getSemanticCallSites().stream().anyMatch(site -> site.getExpression().equals(call.toString())
                            && site.getResolvedTarget().map(t -> t.declaringType().equals("java.util.Objects")).orElse(false))) result.add(i);
                // A prior early return/conditional/loop may bypass later validations.
                if (!statement.isExpressionStmt() && !(statement.isIfStmt() && statement.asIfStmt().getThenStmt().isThrowStmt())) break;
            }
        }
        return result;
    }

    public static Map<String, Set<Integer>> calls(MethodModel caller, Map<String, MethodModel> methods) {
        Map<String, Set<Integer>> result = new HashMap<>();
        for (var site : caller.getSemanticCallSites()) {
            Set<Integer> effects = site.getResolvedTarget().filter(t -> !t.virtualDispatch()).map(t -> {
                if (t.declaringType().equals("java.util.Objects") && t.methodName().equals("requireNonNull")) return Set.of(0);
                var callee = methods.get(t.qualifiedSignature());
                return callee == null ? Set.<Integer>of() : callee.getValidatedParameters();
            }).orElse(Set.of());
            result.merge(site.getExpression(), effects, (left, right) -> {
                Set<Integer> common = new LinkedHashSet<>(left); common.retainAll(right); return Set.copyOf(common);
            });
        }
        return result;
    }

    public static void refine(String source, Map<String, com.nullguard.analysis.lattice.NullState> state, Map<String, Set<Integer>> contracts) {
        if (contracts.isEmpty()) return;
        try {
            com.github.javaparser.ast.Node ast;
            try { ast = com.nullguard.core.parser.SourceExpressions.parseStatement(source); }
            catch (RuntimeException ex) { ast = com.nullguard.core.parser.SourceExpressions.parseExpression(source); }
            for (var call : ast.findAll(MethodCallExpr.class)) {
                boolean conditional = call.findAncestor(LambdaExpr.class).isPresent();
                com.github.javaparser.ast.Node child = call;
                while (child.getParentNode().isPresent()) {
                    var parent = child.getParentNode().get();
                    if (parent instanceof ConditionalExpr c && child != c.getCondition()) conditional = true;
                    if (parent instanceof BinaryExpr b && child == b.getRight() && (b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)) conditional = true;
                    child = parent;
                }
                if (conditional) continue;
                for (int index : contracts.getOrDefault(call.toString(), Set.of())) if (index < call.getArguments().size()) {
                    String argument = call.getArgument(index).toString();
                    if (call.getArgument(index).isNameExpr() && !com.nullguard.analysis.engine.ExpressionNullability.assignedNames(source).contains(argument))
                        state.put(argument, com.nullguard.analysis.lattice.NullState.NON_NULL);
                }
            }
        } catch (RuntimeException unsupported) { /* retain unknown */ }
    }
}
