package com.nullguard.analysis.extractor;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.analysis.ir.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BasicInstructionExtractor – maps CFG nodes to typed IR instructions.
 *
 * <h3>Null-detection rules</h3>
 * <ul>
 *   <li>Assignment with null RHS  → {@link AssignmentInstruction} with source = "NULL_LITERAL"</li>
 *   <li>Assignment with non-null RHS → {@link AssignmentInstruction} with source = "NON_NULL"</li>
 *   <li>{@code return null}        → {@link ReturnInstruction} with returnValue = "NULL_LITERAL"</li>
 *   <li>Statement with {@code receiver.method(...)} → emits BOTH:
 *       <ol>
 *         <li>a {@link DereferenceInstruction} for the receiver (null-check tracking)</li>
 *         <li>a {@link MethodCallInstruction} with the callee name (call-graph edge building)</li>
 *       </ol>
 *   </li>
 *   <li>Standalone call {@code method(...)} → {@link MethodCallInstruction}</li>
 * </ul>
 */
public final class BasicInstructionExtractor implements InstructionExtractor {

    // x = null;  /  x = foo.orElse(null)  /  Type x = null;
    private static final Pattern NULL_ASSIGN_PATTERN =
            Pattern.compile("(?:^|[\\s(,])([\\w$]+)\\s*(?:[+\\-*/%&|^]?=)(?!=)\\s*null\\s*[;,)]?");

    private static final Pattern OR_ELSE_NULL_PATTERN =
            Pattern.compile("\\.orElse\\(\\s*null\\s*\\)");

    // Grab the LHS variable of any assignment
    private static final Pattern ASSIGNMENT_TARGET_PATTERN =
            Pattern.compile("(?:[\\w<>\\[\\],\\s]+\\s+)?([\\w$]+)\\s*(?:[+\\-*/%&|^]?=)(?!=)");

    // Split  receiver.method(args)  → group(1)=receiver, group(2)=method
    private static final Pattern RECEIVER_METHOD_PATTERN =
            Pattern.compile("([\\w$]+)\\.([\\w$]+)\\s*\\(");

    @Override
    public List<Instruction> extract(ControlFlowModel cfg) {
        List<Instruction> instructions = new ArrayList<>();
        int instrIndex = 0;

        for (ControlFlowNode node : cfg.getNodes().values()) {
            String src       = node.getSourceCode().trim();
            String cfgId     = node.getId();
            String methodSig = cfg.getMethodSignature();
            String baseId    = methodSig + "_" + cfgId + "_";
            int    line      = node.getLineNumber();

            switch (node.getType()) {
                case STATEMENT -> {
                    boolean isAssignment = src.contains("=") && !src.contains("==")
                                           && !src.contains("!=") && !src.contains(">=")
                                           && !src.contains("<=");

                    if (isAssignment) {
                        String target = extractTarget(src);
                        String source = isNullLiteralRhs(src) ? "NULL_LITERAL" : "NON_NULL";
                        instructions.add(new AssignmentInstruction(
                                baseId + (instrIndex++), cfgId, line, target, source));

                        // Extract method calls/dereferences from the RHS
                        int eq = indexOfAssignmentOperator(src);
                        String rhs = src.substring(eq + 1).trim();
                        instrIndex = extractCallsAndDerefs(rhs, baseId, cfgId, line, instrIndex, instructions);

                    } else if (src.contains("(")) {
                        instrIndex = extractCallsAndDerefs(src, baseId, cfgId, line, instrIndex, instructions);
                    }
                    // Pure field-access statements with '.' but no '(' are rare;
                    // skip to avoid false dereference counts.
                }
                case RETURN -> {
                    String retVal = src.replaceFirst("(?i)^return\\s*", "").replace(";", "").trim();
                    String finalVal = "null".equals(retVal) ? "NULL_LITERAL" : retVal;
                    instructions.add(new ReturnInstruction(
                            baseId + (instrIndex++), cfgId, line, finalVal));

                    // Extract any method calls inside the return expression
                    if (retVal.contains("(")) {
                        instrIndex = extractCallsAndDerefs(retVal, baseId, cfgId, line, instrIndex, instructions);
                    }
                }
                case CONDITION ->
                    instructions.add(new ConditionalInstruction(
                            baseId + (instrIndex++), cfgId, line, src));
                case THROW ->
                    instructions.add(new ThrowInstruction(
                            baseId + (instrIndex++), cfgId, line, src));
                default -> { /* ENTRY/EXIT nodes carry no instructions */ }
            }
        }
        return Collections.unmodifiableList(instructions);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static boolean isNullLiteralRhs(String src) {
        if (OR_ELSE_NULL_PATTERN.matcher(src).find()) return true;
        // Simple check: after the first '=' (not ==, !=, >=, <=) the RHS is "null"
        int eq = indexOfAssignmentOperator(src);
        if (eq < 0) return false;
        String rhs = src.substring(eq + 1).trim();
        return rhs.equals("null") || rhs.equals("null;") || rhs.startsWith("null ")
               || rhs.startsWith("null,") || rhs.startsWith("null)");
    }

    /** Returns the index of the assignment '=' that is not part of ==, !=, >=, <=. */
    private static int indexOfAssignmentOperator(String src) {
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '=' ) {
                if (i > 0) {
                    char prev = src.charAt(i - 1);
                    if (prev == '!' || prev == '<' || prev == '>' || prev == '=') continue;
                }
                if (i < src.length() - 1 && src.charAt(i + 1) == '=') continue;
                return i;
            }
        }
        return -1;
    }

    private static String extractTarget(String src) {
        Matcher m = ASSIGNMENT_TARGET_PATTERN.matcher(src);
        if (m.find()) return m.group(1);
        return "target";
    }

    /**
     * If the RHS of an assignment is a method call, return the callee name;
     * e.g. {@code User user = userRepository.findByEmail(email);} → {@code "findByEmail"}.
     * Returns {@code null} if the RHS is not a method call expression.
     */
    private static int extractCallsAndDerefs(String expr, String baseId, String cfgId, int line, int instrIndex, List<Instruction> instructions) {
        Matcher m = RECEIVER_METHOD_PATTERN.matcher(expr);
        boolean foundAny = false;
        while (m.find()) {
            foundAny = true;
            String receiver = m.group(1);
            String callee   = expr.substring(m.start(), expr.indexOf('(', m.start())).trim();
            instructions.add(new DereferenceInstruction(
                    baseId + (instrIndex++), cfgId, line, receiver));
            instructions.add(new MethodCallInstruction(
                    baseId + (instrIndex++), cfgId, line, callee));
        }

        if (!foundAny && expr.contains("(")) {
            String callee = extractCalleeFromSrc(expr);
            if (callee != null && !callee.isEmpty() && callee.matches("[\\w$]+")) {
                instructions.add(new MethodCallInstruction(
                        baseId + (instrIndex++), cfgId, line, callee));
            }
        }
        return instrIndex;
    }

    private static String extractCalleeFromSrc(String src) {
        int p = src.indexOf('(');
        if (p > 0) {
            String before = src.substring(0, p).trim();
            // If there's a dot, the while loop should have caught it.
            return before;
        }
        return null;
    }
}
