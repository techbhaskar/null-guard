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

    /** {@link AssignmentInstruction#source()} marker: RHS is the literal {@code null}. */
    public static final String NULL_LITERAL = "NULL_LITERAL";
    /** Marker: RHS is a method-call result, i.e. nullability is genuinely unknown. */
    public static final String CALL_RESULT = "CALL_RESULT";
    /** Marker: RHS is a literal / constructor / expression that cannot be null. */
    public static final String NON_NULL = "NON_NULL";

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
            Pattern.compile("^([\\w$]+)\\.([\\w$]+)\\s*\\(");

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
                        String target  = extractTarget(src);
                        String rhsCall = extractRhsMethodCall(src);

                        // ── The receiver on the RHS is dereferenced BEFORE the assignment lands.
                        // `String name = user.getName();` is the single most common NPE shape in
                        // Java, and this branch used to emit no DereferenceInstruction at all —
                        // only bare-statement calls (`user.doIt();`) were ever counted. The deref
                        // is emitted first so the forward analyser evaluates it against the state
                        // *before* the target is written (matters for `n = n.next`).
                        String rhsReceiver = extractRhsReceiver(src);
                        if (rhsReceiver != null) {
                            instructions.add(new DereferenceInstruction(
                                    baseId + (instrIndex++), cfgId, line, rhsReceiver));
                        }

                        String source;
                        if (isNullLiteralRhs(src)) {
                            source = NULL_LITERAL;
                        } else if (rhsCall != null) {
                            // A method return is genuinely unknown. Marking it NON_NULL — as this
                            // did — asserts the opposite of what is known and is precisely the
                            // case a null checker exists to flag.
                            source = CALL_RESULT;
                        } else {
                            source = NON_NULL;
                        }
                        instructions.add(new AssignmentInstruction(
                                baseId + (instrIndex++), cfgId, line, target, source));

                        // Also emit a MethodCallInstruction so the call graph can track the callee.
                        if (rhsCall != null) {
                            instructions.add(new MethodCallInstruction(
                                    baseId + (instrIndex++), cfgId, line, rhsCall,
                                    countArguments(src, rhsCall)));
                        }

                    } else if (src.contains("(")) {
                        // Not an assignment – could be:
                        //   a) receiver.method(args)   → DereferenceInstruction + MethodCallInstruction
                        //   b) method(args)            → MethodCallInstruction only
                        Matcher m = RECEIVER_METHOD_PATTERN.matcher(src);
                        if (m.find()) {
                            String receiver = m.group(1);
                            String callee   = src.substring(m.start(), src.indexOf('(', m.start())).trim();
                            // DereferenceInstruction tracks null safety of the receiver
                            instructions.add(new DereferenceInstruction(
                                    baseId + (instrIndex++), cfgId, line, receiver));
                            // MethodCallInstruction feeds the call graph builder
                            instructions.add(new MethodCallInstruction(
                                    baseId + (instrIndex++), cfgId, line, callee,
                                    countArguments(src, callee)));
                        } else {
                            // Standalone call: method(args) — no explicit receiver
                            String callee = extractCalleeFromSrc(src);
                            instructions.add(new MethodCallInstruction(
                                    baseId + (instrIndex++), cfgId, line, callee,
                                    countArguments(src, callee)));
                        }
                    }
                    // Pure field-access statements with '.' but no '(' are rare;
                    // skip to avoid false dereference counts.
                }
                case RETURN -> {
                    String retVal = src.replaceFirst("(?i)^return\\s*", "").replace(";", "").trim();
                    String finalVal = "null".equals(retVal) ? NULL_LITERAL : retVal;
                    instructions.add(new ReturnInstruction(
                            baseId + (instrIndex++), cfgId, line, finalVal));
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
    private static String extractRhsMethodCall(String src) {
        int eq = indexOfAssignmentOperator(src);
        if (eq < 0) return null;
        String rhs = src.substring(eq + 1).trim();
        Matcher m = RECEIVER_METHOD_PATTERN.matcher(rhs);
        if (m.find()) return rhs.substring(m.start(), rhs.indexOf('(', m.start())).trim();
        // Standalone call on RHS: foo(...)
        int p = rhs.indexOf('(');
        if (p > 0) {
            String candidate = rhs.substring(0, p).trim();
            if (candidate.matches("[\\w$]+")) return candidate;
        }
        return null;
    }

    /**
     * If the RHS of an assignment dereferences a receiver, return the receiver variable;
     * e.g. {@code String name = user.getName();} → {@code "user"}.
     *
     * <p>Receivers whose first character is upper-case are skipped: {@code Optional.of(x)},
     * {@code String.valueOf(y)} and friends are static calls on a type name, not dereferences
     * of a variable, and counting them would be a guaranteed false positive.
     *
     * @return the receiver identifier, or {@code null} if the RHS dereferences nothing
     */
    private static String extractRhsReceiver(String src) {
        int eq = indexOfAssignmentOperator(src);
        if (eq < 0) return null;
        String rhs = src.substring(eq + 1).trim();
        Matcher m = RECEIVER_METHOD_PATTERN.matcher(rhs);
        if (!m.find()) return null;
        String receiver = m.group(1);
        if (receiver.isEmpty() || Character.isUpperCase(receiver.charAt(0))) return null;
        return receiver;
    }

    /**
     * Counts the arguments passed at a call site, so the call-graph resolver can discriminate
     * overloads. Without this, {@code foo(int)} and {@code foo(String, String)} are
     * indistinguishable and whichever is iterated first wins.
     *
     * <p>Commas nested inside parentheses, generics, brackets or string/char literals do not
     * separate arguments. Returns {@link MethodCallInstruction#UNKNOWN_ARG_COUNT} when the
     * argument list cannot be located or is unbalanced, which makes the resolver skip arity
     * filtering rather than filter on a wrong number.
     *
     * @param src    full source text of the statement
     * @param callee the callee text, used to find the correct opening parenthesis
     */
    static int countArguments(String src, String callee) {
        if (src == null || callee == null || callee.isEmpty()) {
            return MethodCallInstruction.UNKNOWN_ARG_COUNT;
        }
        int calleeAt = src.indexOf(callee);
        if (calleeAt < 0) return MethodCallInstruction.UNKNOWN_ARG_COUNT;

        int open = src.indexOf('(', calleeAt + callee.length() - 1);
        if (open < 0) return MethodCallInstruction.UNKNOWN_ARG_COUNT;

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

            if (c == '(' || c == '[' || c == '<') {
                depth++;
                if (depth > 1) sawContent = true;
            } else if (c == ')' || c == ']' || c == '>') {
                depth--;
                if (depth == 0) {
                    return sawContent ? count + 1 : 0;
                }
                if (depth < 0) return MethodCallInstruction.UNKNOWN_ARG_COUNT;
            } else if (c == ',' && depth == 1) {
                count++;
                sawContent = true;
            } else if (depth == 1 && !Character.isWhitespace(c)) {
                sawContent = true;
            }
        }
        return MethodCallInstruction.UNKNOWN_ARG_COUNT;   // unbalanced
    }

    private static String extractCalleeFromSrc(String src) {
        int p = src.indexOf('(');
        if (p > 0) {
            String before = src.substring(0, p).trim();
            // If there's a dot, take the part after it
            return before;
        }
        return src;
    }
}
