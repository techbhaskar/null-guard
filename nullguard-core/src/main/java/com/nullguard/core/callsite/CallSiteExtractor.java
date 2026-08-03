package com.nullguard.core.callsite;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.NodeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts call sites directly from a {@link ControlFlowModel}.
 *
 * <h3>Why this is in core</h3>
 * {@code nullguard-callgraph} declared a dependency on {@code nullguard-analysis} purely so that
 * {@code BasicCallGraphBuilder} could run {@code BasicInstructionExtractor} and filter the
 * resulting IR for {@code MethodCallInstruction}. That is a layering inversion: the call graph is
 * a structural fact about the code and has no business being derived from the dataflow IR, which
 * is a later and more specialised representation. It also meant the IR lowering ran twice per
 * method — once for the call graph and once for the null analysis.
 *
 * <p>Call sites are a property of the CFG, which lives in core, so extraction belongs here. The
 * call-graph module now depends only on core, and the analysis module reuses
 * {@link #countArguments(String, String)} rather than keeping a second copy.
 *
 * <h3>Limits</h3>
 * This is regex over source text, not a resolved AST. The receiver pattern is anchored, so a
 * chained call {@code a.b().c()} yields only {@code a.b}, and a nested call {@code foo(bar())}
 * yields only {@code foo}. Those remain known gaps; closing them requires the symbol solver that
 * {@code JavaParserAstParser} configures and never queries.
 */
public final class CallSiteExtractor {

    /** {@code receiver.method(} → group(1)=receiver, group(2)=method */
    private static final Pattern RECEIVER_METHOD =
            Pattern.compile("^([\\w$]+)\\.([\\w$]+)\\s*\\(");

    /** Extracts every call site in the method, in CFG node order. */
    public List<CallSite> extract(ControlFlowModel cfg) {
        List<CallSite> callSites = new ArrayList<>();

        for (ControlFlowNode node : cfg.getNodes().values()) {
            if (node.getType() != NodeType.STATEMENT) continue;

            String src = node.getSourceCode().trim();
            if (!src.contains("(")) continue;

            String candidate = isAssignment(src) ? rightHandSide(src) : src;
            if (candidate == null || !candidate.contains("(")) continue;

            Matcher m = RECEIVER_METHOD.matcher(candidate);
            if (m.find()) {
                String receiver = m.group(1);
                String callee = candidate.substring(m.start(), candidate.indexOf('(', m.start())).trim();
                callSites.add(new CallSite(callee, receiver,
                        countArguments(candidate, callee), node.getId(), node.getLineNumber()));
            } else {
                int paren = candidate.indexOf('(');
                String callee = candidate.substring(0, paren).trim();
                // Guard against garbage like "int x =" being read as a callee, which the old
                // extractor produced whenever an assignment's RHS contained a comparison.
                if (!callee.matches("[\\w$]+")) continue;
                callSites.add(new CallSite(callee, null,
                        countArguments(candidate, callee), node.getId(), node.getLineNumber()));
            }
        }
        return Collections.unmodifiableList(callSites);
    }

    // ── Shared text utilities ─────────────────────────────────────────────────────

    /**
     * Counts arguments at a call site so overloads can be discriminated by arity.
     *
     * <p>Commas nested inside parentheses, brackets, generics or string/char literals do not
     * separate arguments. Returns {@link CallSite#UNKNOWN_ARG_COUNT} when the list cannot be
     * located or is unbalanced — degrading to "unknown" is safe, because a consumer then skips
     * arity filtering rather than filtering on a wrong number.
     *
     * @param src    text containing the call
     * @param callee callee text, used to locate the correct opening parenthesis
     */
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

    /**
     * Index of the assignment {@code =}, skipping {@code ==}, {@code !=}, {@code <=},
     * {@code >=} and compound operators' trailing {@code =}.
     */
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
