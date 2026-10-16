package com.nullguard.analysis.lattice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the source text of a CONDITION node into the null-state refinements it implies on
 * each branch.
 *
 * <h3>Why this exists</h3>
 * The analyser previously had no way to act on a guard. It used a textual
 * {@code condition.contains(varName)} check over the previous three instructions and, if that
 * matched, suppressed the finding — with two defects: {@code contains} matched substrings, so
 * {@code if (username != null)} silenced an unrelated {@code user}; and there was no polarity,
 * so {@code if (x == null) x.f();} — a guaranteed NPE — was reported as <em>guarded</em>.
 *
 * <p>Now that the CFG carries {@link com.nullguard.core.cfg.EdgeType#TRUE_BRANCH} and
 * {@code FALSE_BRANCH} edges, the analyser can apply the correct refinement per branch and
 * the textual heuristic is gone. {@code if (x == null) x.f();} is now a definite finding, and
 * {@code if (x != null) x.f();} is provably clean rather than merely unreported.
 *
 * <p>AST-based boolean refinements retain only facts guaranteed by a branch. Facts from
 * one side of a disjunction or a failed conjunction never leak to the other side.
 */
public final class NullGuardCondition {

    private NullGuardCondition() {
        // utility
    }

    /**
     * The refinements a condition implies.
     *
     * @param onTrue  variable states that hold when the condition is true
     * @param onFalse variable states that hold when it is false
     */
    public record Refinement(Map<String, NullState> onTrue, Map<String, NullState> onFalse) {

        public static Refinement none() {
            return new Refinement(Map.of(), Map.of());
        }

        public boolean isEmpty() {
            return onTrue.isEmpty() && onFalse.isEmpty();
        }
    }

    /**
     * @param condition raw source text of a CONDITION node
     * @return the per-branch refinements, never null; {@link Refinement#none()} when nothing
     *         about nullness can be concluded
     */
    public static Refinement parse(String condition) {
        if (condition == null || condition.isBlank()) return Refinement.none();

        try {
            return expression(com.nullguard.core.parser.SourceExpressions.parseExpression(condition));
        } catch (RuntimeException unsupported) { return Refinement.none(); }
    }

    private static Refinement expression(com.github.javaparser.ast.expr.Expression e) {
        if (e.isEnclosedExpr()) return expression(e.asEnclosedExpr().getInner());
        if (e.isUnaryExpr() && e.asUnaryExpr().getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            var r = expression(e.asUnaryExpr().getExpression());
            return new Refinement(r.onFalse(), r.onTrue());
        }
        if (e.isBinaryExpr()) {
            var b = e.asBinaryExpr(); var op = b.getOperator();
            var l = expression(b.getLeft()); var r = expression(b.getRight());
            if (op == com.github.javaparser.ast.expr.BinaryExpr.Operator.AND)
                return new Refinement(union(l.onTrue(), r.onTrue()), common(l.onFalse(), r.onFalse()));
            if (op == com.github.javaparser.ast.expr.BinaryExpr.Operator.OR)
                return new Refinement(common(l.onTrue(), r.onTrue()), union(l.onFalse(), r.onFalse()));
            if (op == com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS || op == com.github.javaparser.ast.expr.BinaryExpr.Operator.NOT_EQUALS) {
                var value = b.getLeft().isNullLiteralExpr() ? b.getRight() : b.getRight().isNullLiteralExpr() ? b.getLeft() : null;
                if (value != null && (value.isNameExpr() || (value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr()))) {
                    var yes = op == com.github.javaparser.ast.expr.BinaryExpr.Operator.NOT_EQUALS;
                    return new Refinement(Map.of(value.toString(), yes ? NullState.NON_NULL : NullState.NULL), Map.of(value.toString(), yes ? NullState.NULL : NullState.NON_NULL));
                }
            }
        }
        if (e.isInstanceOfExpr()) {
            var i = e.asInstanceOfExpr(); Map<String, NullState> yes = new LinkedHashMap<>();
            yes.put(i.getExpression().toString(), NullState.NON_NULL);
            i.getPattern().ifPresent(p -> yes.put(p.getNameAsString(), NullState.NON_NULL));
            return new Refinement(Map.copyOf(yes), Map.of());
        }
        if (e.isMethodCallExpr()) {
            var c = e.asMethodCallExpr();
            if (c.getScope().map(Object::toString).filter(x -> x.equals("Objects") || x.equals("java.util.Objects")).isPresent()
                    && c.getArguments().size() == 1 && (c.getNameAsString().equals("nonNull") || c.getNameAsString().equals("isNull"))) {
                boolean yes = c.getNameAsString().equals("nonNull"); String v = c.getArgument(0).toString();
                return new Refinement(Map.of(v, yes ? NullState.NON_NULL : NullState.NULL), Map.of(v, yes ? NullState.NULL : NullState.NON_NULL));
            }
        }
        return Refinement.none();
    }
    private static Map<String, NullState> union(Map<String, NullState> a, Map<String, NullState> b) {
        Map<String, NullState> out = new LinkedHashMap<>(a); b.forEach((k,v) -> out.merge(k,v,NullState::merge)); return Map.copyOf(out);
    }
    private static Map<String, NullState> common(Map<String, NullState> a, Map<String, NullState> b) {
        Map<String, NullState> out = new LinkedHashMap<>(); a.forEach((k,v) -> { if (b.get(k) == v) out.put(k,v); }); return Map.copyOf(out);
    }

    /** Facts established by checks that execute unconditionally on normal completion. */
    public static Map<String, NullState> unconditionalNonNull(String source) {
        if (source == null || source.isBlank()) return Map.of();
        try {
            com.github.javaparser.ast.Node ast;
            try { ast = com.nullguard.core.parser.SourceExpressions.parseStatement(source); }
            catch (RuntimeException notStatement) { ast = com.nullguard.core.parser.SourceExpressions.parseExpression(source); }
            Map<String, NullState> result = new LinkedHashMap<>();
            for (var call : ast.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
                String owner = call.getScope().map(Object::toString).orElse("");
                if (!(owner.equals("Objects") || owner.equals("java.util.Objects")) || !call.getNameAsString().equals("requireNonNull") || call.getArguments().isEmpty()) continue;
                boolean conditional = false;
                com.github.javaparser.ast.Node child = call;
                while (child.getParentNode().isPresent()) {
                    var parent = child.getParentNode().get();
                    if (parent instanceof com.github.javaparser.ast.expr.ConditionalExpr c && child != c.getCondition()) conditional = true;
                    if (parent instanceof com.github.javaparser.ast.expr.BinaryExpr b && child == b.getRight()
                            && (b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.AND || b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.OR)) conditional = true;
                    if (parent instanceof com.github.javaparser.ast.expr.LambdaExpr) conditional = true;
                    child = parent;
                }
                if (!conditional) result.put(call.getArgument(0).toString(), NullState.NON_NULL);
            }
            return Map.copyOf(result);
        } catch (RuntimeException unsupported) { return Map.of(); }
    }
}
