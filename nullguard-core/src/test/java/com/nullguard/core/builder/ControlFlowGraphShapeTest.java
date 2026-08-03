package com.nullguard.core.builder;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.nullguard.core.cfg.ControlFlowEdge;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.EdgeType;
import com.nullguard.core.cfg.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape tests for the rewritten CFG builder.
 *
 * <p>The old builder produced a flat chain: every node had one predecessor and one
 * {@link EdgeType#NORMAL} out-edge, and a method-global {@code hitReturn} flag aborted the
 * entire walk at the first return or throw at any depth. Each test below fails against that
 * implementation.
 */
class ControlFlowGraphShapeTest {

    private static ControlFlowModel cfg(String methodSource) {
        MethodDeclaration md = StaticJavaParser.parseMethodDeclaration(methodSource);
        return new BasicControlFlowBuilder().build(md);
    }

    private static boolean hasEdgeOfType(ControlFlowModel cfg, EdgeType type) {
        return cfg.getEdges().stream().anyMatch(e -> e.getType() == type);
    }

    private static long countType(ControlFlowModel cfg, EdgeType type) {
        return cfg.getEdges().stream().filter(e -> e.getType() == type).count();
    }

    private static boolean containsSource(ControlFlowModel cfg, String needle) {
        return cfg.getNodes().values().stream()
                .anyMatch(n -> n.getSourceCode().contains(needle));
    }

    private static long inDegree(ControlFlowModel cfg, String nodeId) {
        return cfg.getEdges().stream().filter(e -> e.getToNodeId().equals(nodeId)).count();
    }

    // ── The show-stopper ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("code after a guard-clause return is still in the graph")
    void guardClauseDoesNotTruncateTheMethod() {
        // The dominant Java idiom, and exactly where null bugs live. The old builder set
        // hitReturn inside the if and dropped everything below it.
        ControlFlowModel c = cfg("""
                void m(Foo a, Bar b) {
                    if (a == null) { return; }
                    b.doIt();
                    Baz z = null;
                    z.boom();
                }
                """);

        assertTrue(containsSource(c, "b.doIt()"), "statement after the guard was dropped");
        assertTrue(containsSource(c, "z.boom()"), "statement after the guard was dropped");
    }

    @Test
    @DisplayName("a throw is wired to a successor rather than left dangling")
    void throwIsConnected() {
        ControlFlowModel c = cfg("void m() { throw new IllegalStateException(); }");

        ControlFlowNode thrown = c.getNodes().values().stream()
                .filter(n -> n.getType() == NodeType.THROW).findFirst().orElseThrow();

        assertTrue(c.getEdges().stream().anyMatch(e -> e.getFromNodeId().equals(thrown.getId())),
                "THROW node had no out-edge at all in the old builder");
        assertTrue(inDegree(c, c.getExitNodeId()) > 0, "EXIT was unreachable");
    }

    // ── Branching ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("if/else emits both branch edges and joins afterwards")
    void ifElseBranchesAndJoins() {
        ControlFlowModel c = cfg("""
                void m(boolean flag) {
                    String x;
                    if (flag) { x = null; } else { x = "a"; }
                    use(x);
                }
                """);

        assertTrue(hasEdgeOfType(c, EdgeType.TRUE_BRANCH));
        assertTrue(hasEdgeOfType(c, EdgeType.FALSE_BRANCH));

        ControlFlowNode join = c.getNodes().values().stream()
                .filter(n -> n.getSourceCode().contains("use(x)")).findFirst().orElseThrow();
        assertEquals(2, inDegree(c, join.getId()),
                "the statement after if/else must be a join with two predecessors");
    }

    @Test
    @DisplayName("if without else still produces a false edge to the join")
    void ifWithoutElseFallsThrough() {
        ControlFlowModel c = cfg("void m(Foo a) { if (a != null) { a.go(); } done(); }");

        assertTrue(hasEdgeOfType(c, EdgeType.TRUE_BRANCH));
        assertTrue(hasEdgeOfType(c, EdgeType.FALSE_BRANCH));

        ControlFlowNode join = c.getNodes().values().stream()
                .filter(n -> n.getSourceCode().contains("done()")).findFirst().orElseThrow();
        assertEquals(2, inDegree(c, join.getId()));
    }

    // ── Loops ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("while loop emits a back-edge to its head")
    void whileEmitsBackEdge() {
        ControlFlowModel c = cfg("void m(Node n) { while (n != null) { n = n.next; } }");
        assertTrue(hasEdgeOfType(c, EdgeType.BACK_EDGE),
                "without a back-edge the analyser cannot iterate a loop to a fixpoint");
    }

    @Test
    @DisplayName("for loop keeps its init and update clauses and emits a back-edge")
    void forKeepsInitAndUpdate() {
        // getInitialization() and getUpdate() were discarded entirely, so
        // `for (Node n = head; n != null; n = n.next)` lost both assignments.
        ControlFlowModel c = cfg("void m(Node head) { for (Node n = head; n != null; n = n.next) { visit(n); } }");

        assertTrue(containsSource(c, "n = head"), "for-init was dropped");
        assertTrue(containsSource(c, "n = n.next"), "for-update was dropped");
        assertTrue(hasEdgeOfType(c, EdgeType.BACK_EDGE));
    }

    @Test
    @DisplayName("do-while loops back to the first body node")
    void doWhileLoopsBack() {
        ControlFlowModel c = cfg("void m() { do { step(); } while (again()); }");
        assertTrue(hasEdgeOfType(c, EdgeType.BACK_EDGE));
    }

    @Test
    @DisplayName("break leaves the loop and continue returns to the head")
    void breakAndContinueAreWired() {
        ControlFlowModel c = cfg("""
                void m(int n) {
                    while (n > 0) {
                        if (n == 5) { break; }
                        if (n == 3) { continue; }
                        n--;
                    }
                    after();
                }
                """);

        // continue contributes an extra back-edge on top of the body's fall-through.
        assertTrue(countType(c, EdgeType.BACK_EDGE) >= 2,
                "continue must produce its own back-edge to the loop head");

        ControlFlowNode after = c.getNodes().values().stream()
                .filter(nd -> nd.getSourceCode().contains("after()")).findFirst().orElseThrow();
        assertTrue(inDegree(c, after.getId()) >= 2,
                "the statement after the loop is reached from both the false edge and the break");
    }

    // ── Switch ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("switch is decomposed instead of becoming one opaque node")
    void switchIsDecomposed() {
        // Previously a switch hit the catch-all branch and became ONE STATEMENT node whose
        // source text was the whole block, which the IR extractor then mis-lowered.
        ControlFlowModel c = cfg("""
                void m(int k) {
                    switch (k) {
                        case 1: a = null; break;
                        case 2: b = "x"; break;
                        default: c = compute();
                    }
                }
                """);

        assertTrue(containsSource(c, "a = null"), "case body was not decomposed");
        assertTrue(containsSource(c, "b = \"x\""), "case body was not decomposed");
        assertFalse(c.getNodes().values().stream()
                        .anyMatch(n -> n.getSourceCode().startsWith("switch (k) {")),
                "the whole switch block must not survive as one node's source text");
    }

    // ── Exceptions ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("try/catch produces exception edges into the catch clause")
    void tryCatchEmitsExceptionEdges() {
        ControlFlowModel c = cfg("""
                void m() {
                    try { risky(); } catch (Exception e) { recover(); }
                    done();
                }
                """);

        assertTrue(hasEdgeOfType(c, EdgeType.EXCEPTION),
                "EXCEPTION was declared but never constructed by the old builder");
        assertTrue(containsSource(c, "recover()"));
    }

    // ── Regression guard on the original assertions ───────────────────────────────

    @Test
    @DisplayName("the trivial method still yields 4 nodes and 3 edges")
    void trivialMethodShapeUnchanged() {
        ControlFlowModel c = cfg("void foo() { int a = 1; return; }");
        assertEquals(4, c.getNodes().size());
        assertEquals(3, c.getEdges().size());
        for (ControlFlowEdge e : c.getEdges()) {
            assertEquals(EdgeType.NORMAL, e.getType(), "a straight-line method has only normal edges");
        }
    }
}
