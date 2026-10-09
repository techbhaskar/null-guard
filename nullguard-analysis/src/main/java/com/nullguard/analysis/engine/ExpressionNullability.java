package com.nullguard.analysis.engine;

import com.github.javaparser.StaticJavaParser;
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
        try { return evaluate(StaticJavaParser.parseExpression(expression), state, returns); }
        catch (RuntimeException unsupported) { return NullState.UNKNOWN; }
    }
    public static NullState evaluate(Expression expression, Map<String, NullState> state, Map<String, NullState> returns) {
        if (expression instanceof NullLiteralExpr) return NullState.NULL;
        if (expression instanceof NameExpr name) return state.getOrDefault(name.getNameAsString(), NullState.UNKNOWN);
        if (expression instanceof EnclosedExpr enclosed) return evaluate(enclosed.getInner(), state, returns);
        if (expression instanceof CastExpr cast) return evaluate(cast.getExpression(), state, returns);
        if (expression instanceof ConditionalExpr conditional)
            return evaluate(conditional.getThenExpr(), state, returns).merge(evaluate(conditional.getElseExpr(), state, returns));
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
    public static String assignmentRhs(String source) {
        try {
            var stmt = StaticJavaParser.parseStatement(source);
            var vars = stmt.findAll(com.github.javaparser.ast.body.VariableDeclarator.class);
            if (!vars.isEmpty()) return vars.get(0).getInitializer().map(Object::toString).orElse("");
            return stmt.findFirst(AssignExpr.class).map(a -> a.getValue().toString()).orElse("");
        } catch (RuntimeException unsupported) { return ""; }
    }
}
