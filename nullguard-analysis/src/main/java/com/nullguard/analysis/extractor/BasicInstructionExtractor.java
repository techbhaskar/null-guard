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
 * BasicInstructionExtractor – maps CFG nodes to IR instructions.
 *
 * <h3>Null-detection rules (Fix 2)</h3>
 * Previously all assignment sources were written as the literal string {@code "source"},
 * so the data-flow analyser never saw a {@code null} literal and intrinsic risk was always 0.
 *
 * Now the extractor:
 * <ul>
 *   <li>Extracts the actual target variable name from the statement text</li>
 *   <li>Marks the source as {@code "NULL_LITERAL"} when the RHS is {@code null} or
 *       ends with {@code .orElse(null)}</li>
 *   <li>Emits a {@link DereferenceInstruction} for member accesses ({@code .}) on variables
 *       that <em>may</em> be null (heuristic: LHS of a prior null-assignment that is not
 *       guarded by an {@code if (x == null)} or {@code if (x != null)} check)</li>
 *   <li>Detects {@code return null} → marks as nullable return</li>
 * </ul>
 */
public final class BasicInstructionExtractor implements InstructionExtractor {

    // Matches:  SomeType var = null;   or   var = null;
    private static final Pattern NULL_ASSIGN_PATTERN =
            Pattern.compile("(?:^|\\s)(\\w+)\\s*=\\s*null\\s*[;,)]");

    // Matches:  .orElse(null)
    private static final Pattern OR_ELSE_NULL_PATTERN =
            Pattern.compile("\\.orElse\\(\\s*null\\s*\\)");

    // Matches the target variable of an assignment:  type? var =   or just   var =
    private static final Pattern ASSIGNMENT_TARGET_PATTERN =
            Pattern.compile("(?:[\\w<>\\[\\]]+\\s+)?(\\w+)\\s*(?:[+\\-*/%&|^]?=)(?!=)");

    @Override
    public List<Instruction> extract(ControlFlowModel cfg) {
        List<Instruction> instructions = new ArrayList<>();
        int instrIndex = 0;

        for (ControlFlowNode node : cfg.getNodes().values()) {
            String src      = node.getSourceCode();
            String cfgId    = node.getId();
            String methodSig = cfg.getMethodSignature();
            String id        = methodSig + "_" + cfgId + "_" + (instrIndex++);
            int    line      = node.getLineNumber();

            switch (node.getType()) {
                case STATEMENT -> {
                    boolean isAssignment = src.contains("=") && !src.contains("==");

                    if (isAssignment) {
                        // ── Detect null literal RHS ──────────────────────────────────
                        String target  = extractTarget(src);
                        String source  = isNullLiteralRhs(src) ? "NULL_LITERAL" : "source";
                        instructions.add(new AssignmentInstruction(id, cfgId, line, target, source));

                    } else if (src.contains(".")) {
                        // ── Method call or field access – potential dereference ──────
                        // Emit a DereferenceInstruction; ForwardDataFlowAnalyzer will
                        // only count it as UNGUARDED if the receiver was NULL in the
                        // lattice state at this point.
                        String receiver = extractReceiver(src);
                        instructions.add(new DereferenceInstruction(id, cfgId, line, receiver));

                    } else if (src.contains("(")) {
                        instructions.add(new MethodCallInstruction(id, cfgId, line, src.trim()));
                    }
                }
                case RETURN -> {
                    // Detect:  return null;
                    String retVal = src.replaceAll("return\\s*", "").replace(";", "").trim();
                    boolean isNullReturn = retVal.equals("null");
                    instructions.add(new ReturnInstruction(id, cfgId, line,
                            isNullReturn ? "NULL_LITERAL" : retVal));
                }
                case CONDITION ->
                    instructions.add(new ConditionalInstruction(id, cfgId, line, src.trim()));
                case THROW ->
                    instructions.add(new ThrowInstruction(id, cfgId, line, src.trim()));
                default -> { /* ENTRY/EXIT – no instruction */ }
            }
        }
        return Collections.unmodifiableList(instructions);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the statement's RHS is the literal {@code null}
     * or contains {@code .orElse(null)}.
     */
    private static boolean isNullLiteralRhs(String src) {
        Matcher m = NULL_ASSIGN_PATTERN.matcher(src);
        if (m.find()) return true;
        return OR_ELSE_NULL_PATTERN.matcher(src).find();
    }

    /**
     * Extracts the assignment target variable name, or falls back to {@code "target"}.
     */
    private static String extractTarget(String src) {
        Matcher m = ASSIGNMENT_TARGET_PATTERN.matcher(src.trim());
        if (m.find()) {
            return m.group(1);
        }
        return "target";
    }

    /**
     * Extracts the receiver object of a member-access expression, or falls back to {@code "var"}.
     * E.g. {@code user.getPhoneVerified()} → {@code "user"}.
     */
    private static String extractReceiver(String src) {
        String trimmed = src.trim();
        int dot = trimmed.indexOf('.');
        if (dot > 0) {
            // Strip leading type casts / keywords
            String candidate = trimmed.substring(0, dot).trim();
            // Take the last token (the actual variable name)
            String[] tokens = candidate.split("\\s+");
            if (tokens.length > 0) {
                String last = tokens[tokens.length - 1];
                if (last.matches("[a-zA-Z_$][\\w$]*")) {
                    return last;
                }
            }
        }
        return "var";
    }
}
