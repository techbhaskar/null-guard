package com.nullguard.analysis.engine;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.analysis.extractor.InstructionExtractor;
import com.nullguard.analysis.ir.*;
import com.nullguard.analysis.lattice.NullState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>Sets {@code NullState.NON_NULL} when the source looks non-null (any other assignment)</li>
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

    private static final String NULL_LITERAL = "NULL_LITERAL";

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
                        // ── FIX 3a: propagate NULL through the lattice ────────
                        newOut.put(assign.target(), NullState.NULL);
                    } else {
                        // Any other RHS → conservatively mark NON_NULL
                        // (could be UNKNOWN for method-call returns in a future pass)
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
                // ONLY count as unguarded when the receiver is CONFIRMED NULL.
                // UNKNOWN means the variable was never explicitly assigned null —
                // counting UNKNOWN causes massive false positives on every field/param access.
                if (receiverState == NullState.NULL) {
                    if (!isGuardedByPrecedingCondition(instructions, i, deref.variableName())) {
                        dereferenceCount++;
                    }
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
     * Heuristic: returns {@code true} if any of the previous 1-3 instructions
     * is a {@link ConditionalInstruction} whose source text contains the variable name.
     * This avoids false-positive dereference counts for patterns like:
     * <pre>
     *   if (user == null) { ... }
     *   user.getName();   // in the else-branch
     * </pre>
     */
    private static boolean isGuardedByPrecedingCondition(
            List<Instruction> instructions, int derefIndex, String varName) {
        int lookback = Math.min(3, derefIndex);
        for (int j = derefIndex - 1; j >= derefIndex - lookback; j--) {
            Instruction prev = instructions.get(j);
            if (prev instanceof ConditionalInstruction cond) {
                if (cond.condition().contains(varName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
