package com.nullguard.analysis.engine;

import com.github.javaparser.ast.expr.*;
import com.nullguard.analysis.lattice.NullState;
import java.util.Map;

/** Conservative expression evaluation shared by summaries and boundary checks. */
public final class ExpressionNullability {
    private ExpressionNullability() {}
    public static String callKey(MethodCallExpr call) {
        String scope = call.getScope().filter(NameExpr.class::isInstance).map(Object::toString).map(s -> s + ".").orElse("");
        return scope + call.getNameAsString() + "/" + call.getArguments().size();
    }
    public static NullState evaluate(String expression, Map<String, NullState> state, Map<String, NullState> returns) {
        try { return evaluate(com.nullguard.core.parser.SourceExpressions.parseExpression(expression), state, returns); }
        catch (RuntimeException unsupported) { return NullState.UNKNOWN; }
    }
    public static NullState evaluate(Expression expression, Map<String, NullState> state, Map<String, NullState> returns) {
        if (expression instanceof NullLiteralExpr) return NullState.NULL;
        if (expression instanceof NameExpr name) return state.getOrDefault(name.getNameAsString(), NullState.UNKNOWN);
        if (expression instanceof FieldAccessExpr field && field.getScope().isThisExpr())
            return state.getOrDefault(field.toString(), NullState.UNKNOWN);
        if (expression instanceof EnclosedExpr enclosed) return evaluate(enclosed.getInner(), state, returns);
        if (expression instanceof CastExpr cast) return evaluate(cast.getExpression(), state, returns);
        if (expression instanceof ConditionalExpr conditional)
            return evaluate(conditional.getThenExpr(), refined(state, conditional.getCondition(), true), returns).merge(evaluate(conditional.getElseExpr(), refined(state, conditional.getCondition(), false), returns));
        if (expression instanceof MethodCallExpr call) {
            if (call.getNameAsString().equals("requireNonNull") && call.getScope().map(Object::toString).orElse("").endsWith("Objects"))
                return NullState.NON_NULL;
            return returns.getOrDefault(callKey(call), NullState.UNKNOWN);
        }
        if (expression instanceof LiteralExpr || expression instanceof ObjectCreationExpr
                || expression instanceof ArrayCreationExpr || expression instanceof ThisExpr
                || expression instanceof BinaryExpr || expression instanceof UnaryExpr) return NullState.NON_NULL;
        return NullState.UNKNOWN;
    }
    public static Map<String, NullState> refined(Map<String, NullState> state, Expression condition, boolean truth) {
        var result = new java.util.LinkedHashMap<>(state);
        var r = com.nullguard.analysis.lattice.NullGuardCondition.parse(condition.toString());
        result.putAll(truth ? r.onTrue() : r.onFalse()); return result;
    }

    /** Branch facts apply only at this expression, never to the whole containing statement. */
    public static Map<String, NullState> atExpression(com.github.javaparser.ast.Node expression, Map<String, NullState> state) {
        var result = new java.util.LinkedHashMap<>(state);
        com.github.javaparser.ast.Node child = expression;
        while (child.getParentNode().isPresent()) {
            var parent = child.getParentNode().get();
            if (parent instanceof ConditionalExpr c && child != c.getCondition())
                result.putAll(refined(Map.of(), c.getCondition(), child == c.getThenExpr()));
            if (parent instanceof BinaryExpr b && child == b.getRight()) {
                if (b.getOperator() == BinaryExpr.Operator.AND) result.putAll(refined(Map.of(), b.getLeft(), true));
                if (b.getOperator() == BinaryExpr.Operator.OR) result.putAll(refined(Map.of(), b.getLeft(), false));
            }
            child = parent;
        }
        return result;
    }
    public static Map<String, NullState> atReceiver(String source, String receiver, Map<String, NullState> state) {
        try {
            com.github.javaparser.ast.Node ast;
            try { ast = com.nullguard.core.parser.SourceExpressions.parseStatement(source); }
            catch (RuntimeException ex) { ast = com.nullguard.core.parser.SourceExpressions.parseExpression(source); }
            Map<String, NullState> result = null;
            for (var c : ast.findAll(MethodCallExpr.class)) {
                if (!c.getScope().map(Object::toString).orElse("").equals(receiver)) continue;
                var local = atExpression(c, state);
                if (result == null) result = new java.util.LinkedHashMap<>(local);
                else {
                    var previous = result;
                    previous.replaceAll((k,v) -> v.merge(local.getOrDefault(k, NullState.UNKNOWN)));
                }
            }
            return result == null ? state : result;
        } catch (RuntimeException unsupported) { return state; }
    }
    public static Map<String, NullState> survivingReceivers(String source) {
        try {
            com.github.javaparser.ast.Node ast;
            try { ast = com.nullguard.core.parser.SourceExpressions.parseStatement(source); }
            catch (RuntimeException ex) { ast = com.nullguard.core.parser.SourceExpressions.parseExpression(source); }
            var result = new java.util.LinkedHashMap<String, NullState>();
            for (var call : ast.findAll(MethodCallExpr.class)) {
                boolean guaranteed = true;
                com.github.javaparser.ast.Node child = call;
                while (child.getParentNode().isPresent()) {
                    var parent = child.getParentNode().get();
                    if (parent instanceof ConditionalExpr c && child != c.getCondition()) guaranteed = false;
                    if (parent instanceof BinaryExpr b && child == b.getRight() && (b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)) guaranteed = false;
                    if (parent instanceof LambdaExpr) guaranteed = false;
                    child = parent;
                }
                if (guaranteed) call.getScope().filter(NameExpr.class::isInstance).ifPresent(scope -> result.put(scope.toString(), NullState.NON_NULL));
            }
            return result;
        } catch (RuntimeException unsupported) { return Map.of(); }
    }
    /** Simple names written by a statement: declared variables and assignment/inc-dec targets. */
    public static java.util.Set<String> assignedNames(String source) {
        try {
            com.github.javaparser.ast.Node ast;
            try { ast = com.nullguard.core.parser.SourceExpressions.parseStatement(source); }
            catch (RuntimeException ex) { ast = com.nullguard.core.parser.SourceExpressions.parseExpression(source); }
            var names = new java.util.HashSet<String>();
            ast.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(v -> names.add(v.getNameAsString()));
            ast.findAll(AssignExpr.class).forEach(a -> names.add(written(a.getTarget())));
            var steps = java.util.EnumSet.of(UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
                    UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT);
            ast.findAll(UnaryExpr.class).stream().filter(u -> steps.contains(u.getOperator()))
                    .forEach(u -> names.add(written(u.getExpression())));
            names.remove(null);
            return names;
        } catch (RuntimeException unsupported) {
            // Unknown shape: claim everything is written so no receiver fact is exported.
            return ALL;
        }
    }
    private static String written(Expression target) {
        if (target.isNameExpr()) return target.asNameExpr().getNameAsString();
        if (target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()) return target.toString();
        return null;
    }
    private static final java.util.Set<String> ALL = new java.util.AbstractSet<>() {
        @Override public boolean contains(Object o) { return true; }
        @Override public java.util.Iterator<String> iterator() { return java.util.Collections.emptyIterator(); }
        @Override public int size() { return 0; }
    };

    public static String assignmentRhs(String source) {
        try {
            var stmt = com.nullguard.core.parser.SourceExpressions.parseStatement(source);
            var vars = stmt.findAll(com.github.javaparser.ast.body.VariableDeclarator.class);
            if (!vars.isEmpty()) return vars.get(0).getInitializer().map(Object::toString).orElse("");
            return stmt.findFirst(AssignExpr.class).map(a -> a.getValue().toString()).orElse("");
        } catch (RuntimeException unsupported) { return ""; }
    }
}
