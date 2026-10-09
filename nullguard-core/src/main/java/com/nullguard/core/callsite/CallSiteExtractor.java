package com.nullguard.core.callsite;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.NodeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Extracts method-call sites directly from CFG node source using JavaParser's AST.
 *
 * <p>Call sites are structural facts and therefore belong in core rather than in the later
 * data-flow IR. AST extraction is important here: textual matching loses nested calls such as
 * {@code client.send(mapper.map(value))} and calls contained in {@code return}, condition and
 * {@code throw} expressions.
 *
 * <p>This extractor deliberately does not resolve target types. It preserves the receiver and
 * method name written at the call site; the call-graph resolver is responsible for mapping that
 * structural call to project methods.
 */
public final class CallSiteExtractor {

    /** Extracts every call site in CFG/source order. */
    public List<CallSite> extract(ControlFlowModel cfg) {
        List<CallSite> callSites = new ArrayList<>();

        for (ControlFlowNode node : cfg.getNodes().values()) {
            if (!canContainCalls(node.getType())) continue;

            String source = node.getSourceCode().trim();
            if (!source.contains("(")) continue;

            Node parsed = parseNode(source);
            if (parsed == null) continue;

            List<MethodCallExpr> calls = new ArrayList<>(parsed.findAll(MethodCallExpr.class));
            calls.sort(Comparator
                    .comparingInt((MethodCallExpr call) -> call.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE))
                    .thenComparingInt(call -> call.getBegin().map(p -> p.column).orElse(Integer.MAX_VALUE))
                    .thenComparing(call -> call.toString()));

            for (MethodCallExpr call : calls) {
                String receiver = call.getScope()
                        .map(Object::toString)
                        .orElse(null);
                // Preserve a simple receiver (service.process) because it is useful to the
                // resolver, but do not serialize an entire chained expression into an ID such
                // as stream.filter(predicate).map. Complex scopes have no stable receiver name;
                // resolving their terminal method by name/arity is safer until type solving is
                // introduced.
                String callee = call.getScope()
                        .filter(NameExpr.class::isInstance)
                        .map(NameExpr.class::cast)
                        .map(scope -> scope.getNameAsString() + "." + call.getNameAsString())
                        .orElseGet(call::getNameAsString);

                callSites.add(new CallSite(
                        callee,
                        receiver,
                        call.getArguments().size(),
                        node.getId(),
                        node.getLineNumber()));
            }
        }

        return Collections.unmodifiableList(callSites);
    }

    private static boolean canContainCalls(NodeType type) {
        return type == NodeType.STATEMENT
                || type == NodeType.RETURN
                || type == NodeType.CONDITION
                || type == NodeType.THROW;
    }

    /**
     * CFG nodes contain either complete statements or bare expressions (notably conditions).
     * Try the statement grammar first, then the expression grammar. An unparseable node is left
     * for later analysis rather than inventing a call from partial text.
     */
    private static Node parseNode(String source) {
        try {
            Statement statement = StaticJavaParser.parseStatement(source);
            return statement;
        } catch (ParseProblemException statementFailure) {
            String expressionSource = stripTrailingSemicolon(source);
            try {
                Expression expression = StaticJavaParser.parseExpression(expressionSource);
                return expression;
            } catch (ParseProblemException expressionFailure) {
                return null;
            }
        }
    }

    private static String stripTrailingSemicolon(String source) {
        String trimmed = source.trim();
        return trimmed.endsWith(";")
                ? trimmed.substring(0, trimmed.length() - 1).trim()
                : trimmed;
    }

    // Kept as shared compatibility utilities for callers that reason about raw text.

    /** Counts arguments at a named call site in raw source text. */
    public static int countArguments(String src, String callee) {
        if (src == null || callee == null || callee.isEmpty()) return CallSite.UNKNOWN_ARG_COUNT;

        int calleeAt = src.indexOf(callee);
        if (calleeAt < 0) return CallSite.UNKNOWN_ARG_COUNT;

        int open = src.indexOf('(', calleeAt + callee.length() - 1);
        if (open < 0) return CallSite.UNKNOWN_ARG_COUNT;

        int depth = 0;
        int count = 0;
        boolean sawContent = false;
        boolean inString = false;
        boolean inChar = false;

        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);

            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') i++;
                else if (c == '\'') inChar = false;
                continue;
            }
            if (c == '"') { inString = true; sawContent = true; continue; }
            if (c == '\'') { inChar = true; sawContent = true; continue; }

            if (c == '(' || c == '[') {
                depth++;
                if (depth > 1) sawContent = true;
            } else if (c == ')' || c == ']') {
                depth--;
                if (depth == 0) return sawContent ? count + 1 : 0;
                if (depth < 0) return CallSite.UNKNOWN_ARG_COUNT;
            } else if (c == ',' && depth == 1) {
                count++;
                sawContent = true;
            } else if (depth == 1 && !Character.isWhitespace(c)) {
                sawContent = true;
            }
        }
        return CallSite.UNKNOWN_ARG_COUNT;
    }

    /** True when the statement is an assignment rather than a comparison. */
    public static boolean isAssignment(String src) {
        return indexOfAssignmentOperator(src) >= 0;
    }

    /** @return text to the right of the assignment operator, or null if there is none */
    public static String rightHandSide(String src) {
        int eq = indexOfAssignmentOperator(src);
        return eq < 0 ? null : src.substring(eq + 1).trim();
    }

    /** Index of an assignment {@code =}, excluding comparison operators. */
    public static int indexOfAssignmentOperator(String src) {
        if (src == null) return -1;
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) != '=') continue;
            if (i > 0) {
                char prev = src.charAt(i - 1);
                if (prev == '!' || prev == '<' || prev == '>' || prev == '=') continue;
            }
            if (i < src.length() - 1 && src.charAt(i + 1) == '=') continue;
            return i;
        }
        return -1;
    }
}
