package com.nullguard.analysis.engine;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.core.builder.BasicControlFlowBuilder;
import com.nullguard.core.cfg.ControlFlowModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression tests for the unguarded-dereference counter — source text through
 * CFG, IR lowering and the forward analysis.
 *
 * <p>Before these fixes the counter was structurally incapable of firing: dereferences on the
 * RHS of an assignment were never lowered to a {@code DereferenceInstruction} at all, and
 * every non-literal RHS was recorded as {@code NON_NULL}, so the only reachable path to a
 * finding was a literal {@code = null} in the same method. The analyser reported zero
 * dereferences on essentially all real code.
 */
class DereferenceDetectionTest {

    private static int derefCountOf(String methodSource) {
        MethodDeclaration md = StaticJavaParser.parseMethodDeclaration(methodSource);
        ControlFlowModel cfg = new BasicControlFlowBuilder().build(md);
        ForwardDataFlowAnalyzer analyzer =
                new ForwardDataFlowAnalyzer(new BasicInstructionExtractor());
        return analyzer.analyze(cfg).getUnguardedDereferences();
    }

    @Test
    @DisplayName("dereference on the RHS of an assignment is counted (the classic NPE shape)")
    void countsDereferenceInsideAssignment() {
        // Previously 0: the isAssignment branch emitted no DereferenceInstruction whatsoever.
        assertTrue(derefCountOf("void m() { String name = user.getName(); }") >= 1,
                "user.getName() on an unguarded receiver must be reported");
    }

    @Test
    @DisplayName("dereference of a variable assigned from a method call is counted")
    void countsDereferenceOfCallResult() {
        // Previously 0: `repo.find()` marked `u` NON_NULL, asserting the opposite of what is known.
        String src = "void m() { User u = repo.find(); u.activate(); }";
        assertTrue(derefCountOf(src) >= 1, "a method-call result is UNKNOWN, not NON_NULL");
    }

    @Test
    @DisplayName("dereference of a confirmed-null local is counted")
    void countsDereferenceOfNullLiteral() {
        assertTrue(derefCountOf("void m() { String s = null; s.length(); }") >= 1);
    }

    @Test
    @DisplayName("an explicit null guard suppresses the finding")
    void nullGuardSuppresses() {
        assertEquals(0, derefCountOf("void m() { if (user != null) { String n = user.getName(); } }"));
    }

    @Test
    @DisplayName("Objects.requireNonNull counts as a guard")
    void requireNonNullSuppresses() {
        assertEquals(0, derefCountOf(
                "void m() { if (Objects.requireNonNull(user) != null) { String n = user.getName(); } }"));
    }

    @Test
    @DisplayName("a guard on a DIFFERENT variable with a shared prefix does not suppress")
    void substringGuardDoesNotSuppress() {
        // The old guard check used condition.contains(varName), so "username" suppressed "user".
        String src = "void m() { if (username != null) { String n = user.getName(); } }";
        assertTrue(derefCountOf(src) >= 1,
                "guard detection must match whole identifiers, not substrings");
    }

    @Test
    @DisplayName("reassignment invalidates an earlier guard")
    void reassignmentInvalidatesGuard() {
        String src = "void m() { if (a != null) { b = 1; } a = svc.find(); a.use(); }";
        assertTrue(derefCountOf(src) >= 1,
                "a guard established before a reassignment says nothing about the new value");
    }

    @Test
    @DisplayName("static calls on a type name are not dereferences")
    void staticCallIsNotADereference() {
        assertEquals(0, derefCountOf("void m() { String s = String.valueOf(7); }"));
    }

    @Test
    @DisplayName("isNullGuardFor matches whole identifiers only")
    void guardMatcherIsIdentifierExact() {
        assertTrue(ForwardDataFlowAnalyzer.isNullGuardFor("user != null", "user"));
        assertTrue(ForwardDataFlowAnalyzer.isNullGuardFor("null == user", "user"));
        assertTrue(ForwardDataFlowAnalyzer.isNullGuardFor("user instanceof Admin", "user"));
        assertTrue(ForwardDataFlowAnalyzer.isNullGuardFor("Objects.requireNonNull(user)", "user"));

        assertTrue(!ForwardDataFlowAnalyzer.isNullGuardFor("username != null", "user"));
        assertTrue(!ForwardDataFlowAnalyzer.isNullGuardFor("user.isActive()", "user"));
    }
}
