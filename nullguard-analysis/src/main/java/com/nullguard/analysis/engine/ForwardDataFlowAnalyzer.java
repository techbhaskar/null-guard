package com.nullguard.analysis.engine;

import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.analysis.extractor.InstructionExtractor;
import com.nullguard.analysis.ir.AssignmentInstruction;
import com.nullguard.analysis.ir.DereferenceInstruction;
import com.nullguard.analysis.ir.Instruction;
import com.nullguard.analysis.ir.ReturnInstruction;
import com.nullguard.analysis.lattice.NullGuardCondition;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.core.cfg.ControlFlowEdge;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.EdgeType;
import com.nullguard.core.cfg.NodeType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ForwardDataFlowAnalyzer — monotone forward null-state analysis over the method CFG.
 *
 * <h3>What this replaces</h3>
 * The previous implementation was named a fixpoint analysis but was a two-pass linear sweep:
 * <pre>
 *   // in[i] = out[i-1]  (linear CFG - no branching yet)
 *   if (i &gt; 0) newIn.putAll(outState.get(instructions.get(i - 1).id()));
 * </pre>
 * The predecessor was the previous element of a {@code List}, not a CFG edge. Consequently
 * {@link NullState#merge} was never called in production, confluence points were overwritten
 * rather than joined, loops were visited once, and the then/else branches of an {@code if}
 * were concatenated so that the textually last write won — reversing the branches reversed
 * the verdict.
 *
 * <h3>How it works now</h3>
 * A standard worklist over the CFG:
 * <ol>
 *   <li>IR instructions are grouped by their CFG node, preserving order within the node.</li>
 *   <li>{@code in[n]} is the pointwise join, via {@link NullState#merge}, of {@code out[p]}
 *       over every predecessor {@code p} — after applying the branch refinement carried by
 *       the edge {@code p → n}.</li>
 *   <li>{@code out[n]} is {@code in[n]} pushed through each instruction in the node.</li>
 *   <li>A node whose out-state changed re-queues its successors. Back-edges make loops
 *       re-iterate, which is why the CFG rewrite had to land first.</li>
 * </ol>
 *
 * <p>Branch refinement is the substantive gain: on the {@link EdgeType#TRUE_BRANCH} out of
 * {@code if (x != null)} the analyser now knows {@code x} is {@link NullState#NON_NULL}, and on
 * the {@link EdgeType#FALSE_BRANCH} it knows {@code x} is {@link NullState#NULL}. That both
 * removes the false positives the old textual guard check was papering over and turns
 * {@code if (x == null) x.f();} — previously reported as <em>guarded</em> — into a finding.
 *
 * <h3>Termination</h3>
 * The lattice is finite (three states over a finite variable set) and both the transfer and
 * the join are monotone, so the worklist terminates. {@link #MAX_ITERATIONS} is a defensive
 * cap only: the old loop had no bound at all, and would have spun forever had two instructions
 * ever collided on an id.
 */
public final class ForwardDataFlowAnalyzer implements NullStateAnalyzer {

    private static final String NULL_LITERAL = BasicInstructionExtractor.NULL_LITERAL;
    private static final String CALL_RESULT = BasicInstructionExtractor.CALL_RESULT;

    /** Defensive bound on worklist pops; a correct run converges far below this. */
    private static final int MAX_ITERATIONS = 100_000;

    private final InstructionExtractor extractor;
    private final Map<String, NullState> initialStates;
    private final Map<String, NullState> callReturns;
    private final boolean primitiveReturn;

    public ForwardDataFlowAnalyzer(InstructionExtractor extractor) {
        this(extractor, Map.of(), Map.of());
    }

    public ForwardDataFlowAnalyzer(InstructionExtractor extractor, Map<String, NullState> initialStates,
                                   Map<String, NullState> callReturns) {
        this(extractor, initialStates, callReturns, false);
    }
    public ForwardDataFlowAnalyzer(InstructionExtractor extractor, Map<String, NullState> initialStates,
                                   Map<String, NullState> callReturns, boolean primitiveReturn) {
        this.extractor = extractor;
        this.initialStates = Map.copyOf(initialStates);
        this.callReturns = Map.copyOf(callReturns);
        this.primitiveReturn = primitiveReturn;
    }

    @Override
    public NullAnalysisModel analyze(ControlFlowModel cfg) {
        List<Instruction> instructions = extractor.extract(cfg);

        Map<String, List<Instruction>> byNode = groupByNode(instructions);
        Map<String, List<ControlFlowEdge>> incoming = incomingEdges(cfg);
        Map<String, Set<String>> successors = successors(cfg);

        Map<String, Map<String, NullState>> nodeIn = new LinkedHashMap<>();
        Map<String, Map<String, NullState>> nodeOut = new LinkedHashMap<>();
        for (String nodeId : cfg.getNodes().keySet()) {
            nodeIn.put(nodeId, Map.of());
            nodeOut.put(nodeId, Map.of());
        }

        // ── Worklist to fixpoint ─────────────────────────────────────────────────
        Deque<String> worklist = new ArrayDeque<>(cfg.getNodes().keySet());
        Set<String> queued = new LinkedHashSet<>(cfg.getNodes().keySet());
        int iterations = 0;

        while (!worklist.isEmpty() && iterations++ < MAX_ITERATIONS) {
            String nodeId = worklist.removeFirst();
            queued.remove(nodeId);

            Map<String, NullState> joined = joinPredecessors(cfg, incoming.get(nodeId), nodeOut);
            if (nodeId.equals(cfg.getEntryNodeId())) joined = initialStates;
            Map<String, NullState> out = transferNode(cfg, nodeId, byNode, joined);

            nodeIn.put(nodeId, joined);
            if (!out.equals(nodeOut.get(nodeId))) {
                nodeOut.put(nodeId, out);
                for (String succ : successors.getOrDefault(nodeId, Set.of())) {
                    if (queued.add(succ)) worklist.addLast(succ);
                }
            }
        }

        // ── Report from the converged state ──────────────────────────────────────
        return report(cfg, byNode, nodeIn, instructions);
    }

    // ── Fixpoint machinery ────────────────────────────────────────────────────────

    /**
     * {@code in[n] = join over predecessors p of refine(out[p], edge p→n)}.
     *
     * <p>A variable known in one predecessor but absent from another joins to
     * {@link NullState#UNKNOWN}: "known here, unknown there" is not knowledge.
     */
    private Map<String, NullState> joinPredecessors(ControlFlowModel cfg,
                                                    List<ControlFlowEdge> preds,
                                                    Map<String, Map<String, NullState>> nodeOut) {
        if (preds == null || preds.isEmpty()) return Map.of();

        Map<String, NullState> result = null;
        for (ControlFlowEdge edge : preds) {
            Map<String, NullState> predOut = nodeOut.getOrDefault(edge.getFromNodeId(), Map.of());
            Map<String, NullState> refined = refine(cfg, edge, predOut);

            if (result == null) {
                result = new LinkedHashMap<>(refined);
                continue;
            }
            Map<String, NullState> merged = new LinkedHashMap<>();
            Set<String> keys = new LinkedHashSet<>(result.keySet());
            keys.addAll(refined.keySet());
            for (String key : keys) {
                NullState a = result.getOrDefault(key, NullState.UNKNOWN);
                NullState b = refined.getOrDefault(key, NullState.UNKNOWN);
                merged.put(key, a.merge(b));
            }
            result = merged;
        }
        return result == null ? Map.of() : result;
    }

    /**
     * Applies the null-state knowledge implied by taking a particular edge out of a CONDITION
     * node. This is the polarity fix: the same condition refines differently on the true and
     * false branches, which the old linear model could not express at all.
     */
    private Map<String, NullState> refine(ControlFlowModel cfg,
                                          ControlFlowEdge edge,
                                          Map<String, NullState> state) {
        EdgeType type = edge.getType();
        if (type != EdgeType.TRUE_BRANCH && type != EdgeType.FALSE_BRANCH) return state;

        ControlFlowNode from = cfg.getNodes().get(edge.getFromNodeId());
        if (from == null || from.getType() != NodeType.CONDITION) return state;

        NullGuardCondition.Refinement refinement = NullGuardCondition.parse(from.getSourceCode());
        Map<String, NullState> delta = type == EdgeType.TRUE_BRANCH
                ? refinement.onTrue()
                : refinement.onFalse();
        if (delta.isEmpty()) return state;

        Map<String, NullState> refined = new LinkedHashMap<>(state);
        refined.putAll(delta);
        return refined;
    }

    /** Pushes the node's in-state through every instruction the node contains, in order. */
    private Map<String, NullState> transferNode(ControlFlowModel cfg,
                                                String nodeId,
                                                Map<String, List<Instruction>> byNode,
                                                Map<String, NullState> in) {
        Map<String, NullState> state = new LinkedHashMap<>(in);

        // Evaluating `Objects.requireNonNull(x)` proves x non-null on BOTH successors,
        // because the alternative is that the condition threw.
        ControlFlowNode node = cfg.getNodes().get(nodeId);
        if (node != null && node.getType() == NodeType.CONDITION) {
            state.putAll(NullGuardCondition.unconditionalNonNull(node.getSourceCode()));
        }

        for (Instruction inst : byNode.getOrDefault(nodeId, List.of())) {
            NullState assigned = inst instanceof AssignmentInstruction && node != null
                    ? ExpressionNullability.evaluate(ExpressionNullability.assignmentRhs(node.getSourceCode()), state, callReturns) : null;
            applyTransfer(inst, state);
            if (inst instanceof AssignmentInstruction assign && node != null)
                state.put(assign.target(), assigned);
        }
        if (node != null) state.putAll(NullGuardCondition.unconditionalNonNull(node.getSourceCode()));
        return state;
    }

    /** The transfer function for a single instruction, applied in place. */
    private void applyTransfer(Instruction inst, Map<String, NullState> state) {
        if (inst instanceof AssignmentInstruction assign) {
            if (NULL_LITERAL.equals(assign.source())) {
                state.put(assign.target(), NullState.NULL);
            } else if (CALL_RESULT.equals(assign.source())) {
                // A method return is unknown until interprocedural summaries exist.
                state.put(assign.target(), NullState.UNKNOWN);
            } else {
                state.put(assign.target(), NullState.NON_NULL);
            }
        } else if (inst instanceof DereferenceInstruction deref) {
            // Surviving a dereference proves the receiver was non-null from here on.
            state.put(deref.variableName(), NullState.NON_NULL);
        }
    }

    // ── Reporting ─────────────────────────────────────────────────────────────────

    private NullAnalysisModel report(ControlFlowModel cfg,
                                     Map<String, List<Instruction>> byNode,
                                     Map<String, Map<String, NullState>> nodeIn,
                                     List<Instruction> allInstructions) {
        int dereferenceCount = 0;
        boolean returnsNull = false;
        boolean propagatesNullFromCallee = false;

        LinkedHashMap<String, Map<String, NullState>> instIn = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, NullState>> instOut = new LinkedHashMap<>();

        for (String nodeId : cfg.getNodes().keySet()) {
            Map<String, NullState> state = new LinkedHashMap<>(nodeIn.getOrDefault(nodeId, Map.of()));

            ControlFlowNode node = cfg.getNodes().get(nodeId);
            if (node != null && node.getType() == NodeType.CONDITION) {
                state.putAll(NullGuardCondition.unconditionalNonNull(node.getSourceCode()));
            }

            for (Instruction inst : byNode.getOrDefault(nodeId, List.of())) {
                Map<String, NullState> before = Map.copyOf(state);
                instIn.put(inst.id(), before);

                if (inst instanceof DereferenceInstruction deref) {
                    NullState receiver = ExpressionNullability.evaluate(deref.variableName(), before, callReturns);
                    // NULL is a definite NPE; UNKNOWN is a possible one. Branch refinement now
                    // eliminates the guarded cases, so this no longer needs a textual
                    // isGuardedBy() heuristic to suppress false positives.
                    if (receiver == NullState.NULL || receiver == NullState.UNKNOWN) {
                        dereferenceCount++;
                    }
                }

                if (inst instanceof ReturnInstruction ret && !ret.returnValue().isBlank()) {
                    NullState returned = NULL_LITERAL.equals(ret.returnValue()) ? NullState.NULL
                            : ExpressionNullability.evaluate(ret.returnValue(), before, callReturns);
                    if (returned != NullState.NON_NULL && !primitiveReturn) returnsNull = true;
                }

                if (inst instanceof AssignmentInstruction assign && CALL_RESULT.equals(assign.source()) && node != null
                        && ExpressionNullability.evaluate(ExpressionNullability.assignmentRhs(node.getSourceCode()), before, callReturns) != NullState.NON_NULL) {
                    propagatesNullFromCallee = true;
                }

                applyTransfer(inst, state);
                if (inst instanceof AssignmentInstruction assign && node != null)
                    state.put(assign.target(), ExpressionNullability.evaluate(ExpressionNullability.assignmentRhs(node.getSourceCode()), before, callReturns));
                instOut.put(inst.id(), Map.copyOf(state));
            }
        }

        // Instructions attached to nodes the CFG does not know about would otherwise be
        // missing from the model entirely; record them with empty state rather than drop them.
        for (Instruction inst : allInstructions) {
            instIn.putIfAbsent(inst.id(), Map.of());
            instOut.putIfAbsent(inst.id(), Map.of());
        }

        return new NullAnalysisModel(
                cfg.getMethodSignature(), instIn, instOut,
                dereferenceCount, returnsNull, propagatesNullFromCallee);
    }

    // ── CFG helpers ───────────────────────────────────────────────────────────────

    private static Map<String, List<Instruction>> groupByNode(List<Instruction> instructions) {
        Map<String, List<Instruction>> byNode = new LinkedHashMap<>();
        for (Instruction inst : instructions) {
            byNode.computeIfAbsent(inst.cfgNodeId(), k -> new ArrayList<>()).add(inst);
        }
        return byNode;
    }

    private static Map<String, List<ControlFlowEdge>> incomingEdges(ControlFlowModel cfg) {
        Map<String, List<ControlFlowEdge>> incoming = new LinkedHashMap<>();
        for (ControlFlowEdge edge : cfg.getEdges()) {
            incoming.computeIfAbsent(edge.getToNodeId(), k -> new ArrayList<>()).add(edge);
        }
        return incoming;
    }

    private static Map<String, Set<String>> successors(ControlFlowModel cfg) {
        Map<String, Set<String>> succ = new LinkedHashMap<>();
        for (ControlFlowEdge edge : cfg.getEdges()) {
            succ.computeIfAbsent(edge.getFromNodeId(), k -> new LinkedHashSet<>())
                .add(edge.getToNodeId());
        }
        return succ;
    }
}
