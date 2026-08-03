package com.nullguard.analysis.engine;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.analysis.lattice.NullGuardCondition;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.core.builder.BasicControlFlowBuilder;
import com.nullguard.core.cfg.ControlFlowModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the properties the old linear sweep could not have: joins at confluence points,
 * loop re-iteration, and branch polarity.
 *
 * <p>The previous analyser took {@code in[i] = out[i-1]} by list index, so
 * {@link NullState#merge} was never called in production and the then/else branches of an
 * {@code if} were concatenated — the textually last write won, and reversing the branches
 * reversed the verdict.
 */
class WorklistDataFlowTest {

    private static NullAnalysisModel analyze(String methodSource) {
        MethodDeclaration md = StaticJavaParser.parseMethodDeclaration(methodSource);
        ControlFlowModel cfg = new BasicControlFlowBuilder().build(md);
        return new ForwardDataFlowAnalyzer(new BasicInstructionExtractor()).analyze(cfg);
    }

    private static int derefs(String methodSource) {
        return analyze(methodSource).getUnguardedDereferences();
    }

    // ── Branch polarity ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("dereference on the null branch of a guard is a finding")
    void derefOnNullBranchIsReported() {
        // The old textual guard check reported this as GUARDED: the condition mentioned `x`
        // within the three-instruction lookback, so the finding was suppressed — even though
        // the dereference sits in the branch where x is provably null.
        assertTrue(derefs("void m(Foo x) { if (x == null) { x.f(); } }") >= 1,
                "if (x == null) x.f() is a definite NPE, not a guarded access");
    }

    @Test
    @DisplayName("dereference on the non-null branch is provably clean")
    void derefOnNonNullBranchIsClean() {
        assertEquals(0, derefs("void m(Foo x) { if (x != null) { x.f(); } }"));
    }

    @Test
    @DisplayName("the else branch of a null check is refined too")
    void elseBranchOfNullCheckIsRefined() {
        assertEquals(0, derefs("void m(Foo x) { if (x == null) { handle(); } else { x.f(); } }"));
    }

    @Test
    @DisplayName("Yoda-style and instanceof guards both refine")
    void alternativeGuardForms() {
        assertEquals(0, derefs("void m(Foo x) { if (null != x) { x.f(); } }"));
        assertEquals(0, derefs("void m(Object x) { if (x instanceof Foo) { x.hashCode(); } }"));
    }

    @Test
    @DisplayName("Objects.requireNonNull proves non-null on both successors")
    void requireNonNullRefinesUnconditionally() {
        assertEquals(0, derefs(
                "void m(Foo x) { if (Objects.requireNonNull(x) != null) { x.f(); } }"));
    }

    // ── Joins ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("branch order does not change the verdict")
    void resultIsIndependentOfBranchOrder() {
        // Concatenation made this order-dependent: whichever assignment came textually last won.
        int a = derefs("void m(boolean c) { Foo x; if (c) { x = null; } else { x = new Foo(); } x.f(); }");
        int b = derefs("void m(boolean c) { Foo x; if (c) { x = new Foo(); } else { x = null; } x.f(); }");
        assertEquals(a, b, "swapping the branches must not change the analysis result");
        assertTrue(a >= 1, "one branch leaves x null, so the join is not provably non-null");
    }

    @Test
    @DisplayName("non-null on both branches joins to non-null")
    void bothBranchesNonNullJoinsClean() {
        assertEquals(0, derefs(
                "void m(boolean c) { Foo x; if (c) { x = new Foo(); } else { x = new Foo(); } x.f(); }"));
    }

    // ── Loops ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a loop converges and its guard still refines")
    void loopConverges() {
        // With a back-edge the head is re-evaluated; without one the body was visited once.
        // The while condition guards the dereference, so the fixpoint is clean.
        assertEquals(0, derefs("void m(Node n) { while (n != null) { n.visit(); } }"));
    }

    @Test
    @DisplayName("a value nulled inside a loop is visible on the next iteration")
    void loopCarriesStateAcrossBackEdge() {
        int count = derefs("void m(Node n) { while (cond()) { n.visit(); n = null; } }");
        assertTrue(count >= 1, "the back-edge must carry n=null back to the loop head");
    }

    // ── Condition parsing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("guard parsing is identifier-exact and polarity-aware")
    void conditionParsing() {
        var r = NullGuardCondition.parse("user != null");
        assertEquals(NullState.NON_NULL, r.onTrue().get("user"));
        assertEquals(NullState.NULL, r.onFalse().get("user"));

        var inverted = NullGuardCondition.parse("user == null");
        assertEquals(NullState.NULL, inverted.onTrue().get("user"));
        assertEquals(NullState.NON_NULL, inverted.onFalse().get("user"));

        // "username" must not refine "user"
        assertTrue(NullGuardCondition.parse("username != null").onTrue().get("user") == null);

        // A disjunction proves nothing definite on either branch.
        assertTrue(NullGuardCondition.parse("a != null || b != null").isEmpty());
    }

    // ── Termination ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("nested loops and branches terminate")
    void nestedControlFlowTerminates() {
        // Guards against a regression that reintroduces an unbounded iteration.
        assertTrue(derefs("""
                void m(Node a, Node b) {
                    for (int i = 0; i < 10; i++) {
                        while (a != null) {
                            if (b == null) { b = a.next; } else { b = null; }
                            a = a.next;
                        }
                    }
                }
                """) >= 0);
    }
}
