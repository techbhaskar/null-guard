package com.nullguard.analysis.engine;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.analysis.extractor.InstructionExtractor;
import com.nullguard.analysis.ir.*;
import com.nullguard.analysis.lattice.NullState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ForwardDataFlowAnalyzer – fixpoint forward data-flow pass over a method's CFG.
 *
 * <h3>What changed (Fix 3)</h3>
 * The original implementation stored {@code NullState.UNKNOWN} for every assignment
 * regardless of the RHS, so {@code NullState.NULL} was never in the lattice state and
 * the unguarded-dereference counter was never incremented.
 *
 * <p>Now the analyser:
 * <ul>
 *   <li>Sets {@code NullState.NULL} for the target variable when the source is
 *       {@code "NULL_LITERAL"} (set by the fixed {@code BasicInstructionExtractor})</li>
 *   <li>Sets {@code NullState.UNKNOWN} when the RHS is a method-call result ({@code "CALL_RESULT"})</li>
 *   <li>Sets {@code NullState.NON_NULL} only for literals, constructors and other RHS forms
 *       that provably cannot be null</li>
 *   <li>Counts a {@link DereferenceInstruction} as <em>unguarded</em> when the receiver
 *       variable was {@code NullState.NULL} or {@code NullState.UNKNOWN} in the in-state
 *       at that point</li>
 *   <li>Detects {@code return null} (retVal == {@code "NULL_LITERAL"}) to set
 *       {@code nullableReturn = true}</li>
 *   <li>Sets {@code propagatesNullFromCallee = true} when any in-parameter feeds a
 *       null-capable path (heuristic: at least one UNKNOWN assignment exists)</li>
 * </ul>
 */
public final class ForwardDataFlowAnalyzer implements NullStateAnalyzer {

    private static final String NULL_LITERAL = BasicInstructionExtractor.NULL_LITERAL;
    private static final String CALL_RESULT  = BasicInstructionExtractor.CALL_RESULT;

    private final InstructionExtractor extractor;

    public ForwardDataFlowAnalyzer(InstructionExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public NullAnalysisModel analyze(ControlFlowModel cfg) {
        List<Instruction> instructions = extractor.extract(cfg);

        LinkedHashMap<String, Map<String, NullState>> inState  = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, NullState>> outState = new LinkedHashMap<>();

        for (Instruction inst : instructions) {
            inState.put(inst.id(),  new LinkedHashMap<>());
            outState.put(inst.id(), new LinkedHashMap<>());
        }

        // ── Fixpoint forward propagation ─────────────────────────────────────
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < instructions.size(); i++) {
                Instruction inst = instructions.get(i);

                // in[i] = out[i-1]  (linear CFG – no branching yet)
                Map<String, NullState> newIn = new LinkedHashMap<>();
                if (i > 0) {
                    newIn.putAll(outState.get(instructions.get(i - 1).id()));
                }

                Map<String, NullState> newOut = new LinkedHashMap<>(newIn);

                // Transfer function
                if (inst instanceof AssignmentInstruction assign) {
                    if (NULL_LITERAL.equals(assign.source())) {
                        newOut.put(assign.target(), NullState.NULL);
                    } else if (CALL_RESULT.equals(assign.source())) {
                        // A method return is genuinely unknown until interprocedural summaries
                        // land. This branch previously fell through to NON_NULL, which asserted
                        // that `User u = repo.findByEmail(e);` can never be null — the exact
                        // claim a null checker exists to refute.
                        newOut.put(assign.target(), NullState.UNKNOWN);
                    } else {
                        // Literal, constructor, or arithmetic RHS → cannot be null.
                        newOut.put(assign.target(), NullState.NON_NULL);
                    }
                }
                // ConditionalInstruction: a null-guard flips the state on the true branch.
                // Full branch-splitting requires a CFG with proper successor edges.
                // For now treat the conditional as transparent (no state kill).

                if (!outState.get(inst.id()).equals(newOut)) {
                    outState.put(inst.id(), newOut);
                    changed = true;
                }
                inState.put(inst.id(), newIn);
            }
        }

        // ── Count unguarded dereferences + nullable-return detection ─────────
        int     dereferenceCount          = 0;
        boolean returnsNull               = false;
        boolean propagatesNullFromCallee  = false;

        for (int i = 0; i < instructions.size(); i++) {
            Instruction inst = instructions.get(i);
            Map<String, NullState> state = inState.get(inst.id());

            if (inst instanceof DereferenceInstruction deref) {
                NullState receiverState = state.getOrDefault(deref.variableName(), NullState.UNKNOWN);
                // Count a dereference as unguarded when the receiver is CONFIRMED NULL or
                // NOT KNOWN to be non-null. Restricting this to NULL made the counter
                // unreachable in practice: the only way to reach NULL is a literal `= null`
                // in the same method, so the tool's headline metric never fired.
                //
                // TRADE-OFF (deliberate): UNKNOWN covers every unassigned parameter and field,
                // so this is a high-recall / low-precision setting and will report on ordinary
                // parameter dereferences. isGuardedBy(...) below is now the primary
                // false-positive suppressor, which is why it was hardened. To go back to
                // high-precision, drop `|| receiverState == NullState.UNKNOWN`.
                boolean nullCapable = receiverState == NullState.NULL
                                   || receiverState == NullState.UNKNOWN;
                if (nullCapable && !isGuardedBy(instructions, i, deref.variableName())) {
                    dereferenceCount++;
                }
            }

            if (inst instanceof ReturnInstruction ret) {
                if (NULL_LITERAL.equals(ret.returnValue())) {
                    returnsNull = true;
                }
            }

            if (inst instanceof AssignmentInstruction assign) {
                if (NullState.NULL == state.getOrDefault(assign.target(), NullState.UNKNOWN)) {
                    propagatesNullFromCallee = true;
                }
            }
        }

        return new NullAnalysisModel(
                cfg.getMethodSignature(),
                inState,
                outState,
                dereferenceCount,
                returnsNull,
                propagatesNullFromCallee
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if some preceding instruction establishes a null guard on
     * {@code varName}. This is the primary false-positive suppressor now that UNKNOWN
     * receivers are counted, so it replaces the previous three-instruction
     * {@code condition.contains(varName)} substring test, which was wrong in two ways:
     * <ul>
     *   <li>{@code contains} matched substrings, so {@code if (username != null)} suppressed
     *       findings on an unrelated variable named {@code user}.</li>
     *   <li>The window of 3 was arbitrary; a guard four statements back was invisible.</li>
     * </ul>
     *
     * <p>The scan walks backwards over all preceding instructions and stops at the point
     * where {@code varName} is reassigned — a guard before a reassignment says nothing about
     * the new value.
     *
     * <p><b>Known limitation.</b> Polarity is not resolved: {@code if (x == null) x.f();}
     * is treated as guarded even though the dereference sits in the branch where {@code x} is
     * null. That is not fixable here — {@code BasicControlFlowBuilder} emits a flat linear
     * chain with no TRUE_BRANCH/FALSE_BRANCH edges, so which branch an instruction belongs to
     * is simply not represented. Until branch edges exist, treating any null-comparison as a
     * guard is the precision-favouring choice.
     */
    private static boolean isGuardedBy(
            List<Instruction> instructions, int derefIndex, String varName) {
        if (varName == null || varName.isEmpty()) return false;

        for (int j = derefIndex - 1; j >= 0; j--) {
            Instruction prev = instructions.get(j);

            // A reassignment invalidates every guard established before it.
            if (prev instanceof AssignmentInstruction assign
                    && varName.equals(assign.target())) {
                return false;
            }

            if (prev instanceof ConditionalInstruction cond
                    && isNullGuardFor(cond.condition(), varName)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code condition} is a null/non-null test naming exactly {@code varName}. */
    static boolean isNullGuardFor(String condition, String varName) {
        if (condition == null) return false;
        String v = Pattern.quote(varName);
        // x != null / x == null  (and the Yoda forms)
        if (Pattern.compile("\\b" + v + "\\b\\s*[!=]=\\s*null").matcher(condition).find()) return true;
        if (Pattern.compile("null\\s*[!=]=\\s*\\b" + v + "\\b").matcher(condition).find()) return true;
        // Objects.requireNonNull(x) / Objects.nonNull(x) / Objects.isNull(x)
        if (Pattern.compile("(?:requireNonNull|nonNull|isNull)\\s*\\(\\s*\\b" + v + "\\b")
                .matcher(condition).find()) return true;
        // x instanceof Foo  — implies x is non-null
        if (Pattern.compile("\\b" + v + "\\b\\s+instanceof\\b").matcher(condition).find()) return true;
        return false;
    }
}
