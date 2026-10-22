package com.nullguard.analysis.engine;

import com.nullguard.analysis.lattice.NullGuardCondition;
import com.nullguard.analysis.lattice.NullState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BranchFactsTest {
    @Test void compoundConditionsRetainOnlyGuaranteedFacts() {
        var conjunction = NullGuardCondition.parse("x != null && y != null");
        assertEquals(Map.of("x", NullState.NON_NULL, "y", NullState.NON_NULL), conjunction.onTrue());
        assertTrue(conjunction.onFalse().isEmpty());
        var disjunction = NullGuardCondition.parse("x == null || y == null");
        assertTrue(disjunction.onTrue().isEmpty());
        assertEquals(Map.of("x", NullState.NON_NULL, "y", NullState.NON_NULL), disjunction.onFalse());
        assertEquals(Map.of("x", NullState.NULL), NullGuardCondition.parse("!(null != x)").onTrue());
        assertEquals(Map.of("this.value", NullState.NON_NULL), NullGuardCondition.parse("this.value != null").onTrue());
        assertEquals(Map.of("x", NullState.NON_NULL), NullGuardCondition.parse("Objects.nonNull(x)").onTrue());
        assertEquals(Map.of("x", NullState.NULL), NullGuardCondition.parse("java.util.Objects.isNull(x)").onTrue());
        assertEquals(Map.of("x", NullState.NON_NULL), NullGuardCondition.parse("x != null || x != null").onTrue());
        assertEquals(Map.of("x", NullState.UNKNOWN), NullGuardCondition.parse("x == null && x != null").onTrue());
        var pattern = NullGuardCondition.parse("x instanceof String text");
        assertEquals(Map.of("x", NullState.NON_NULL, "text", NullState.NON_NULL), pattern.onTrue());
        for (String text : Arrays.asList(null, "", "(", "a == b", "a.b == null", "Objects.other(x)", "other.isNull(x)", "Objects.isNull()"))
            assertTrue(NullGuardCondition.parse(text).isEmpty(), String.valueOf(text));
    }

    @Test void conditionalChecksDoNotLeakIntoUnconditionalFacts() {
        assertEquals(Map.of("x", NullState.NON_NULL), NullGuardCondition.unconditionalNonNull("Objects.requireNonNull(x);"));
        assertEquals(Map.of("x", NullState.NON_NULL), NullGuardCondition.unconditionalNonNull("java.util.Objects.requireNonNull(x)"));
        for (String source : Arrays.asList(null, "", "(", "flag && Objects.requireNonNull(x) != null",
                "flag || Objects.requireNonNull(x) != null", "flag ? Objects.requireNonNull(x) : null",
                "() -> Objects.requireNonNull(x)", "other.requireNonNull(x)", "Objects.requireNonNull()"))
            assertTrue(NullGuardCondition.unconditionalNonNull(source).isEmpty(), String.valueOf(source));
    }

    @Test void receiverFactsAreLocalToBranchesAndMergeAcrossRepeatedCalls() {
        assertEquals(NullState.NON_NULL, ExpressionNullability.atReceiver("x != null && x.ready()", "x", Map.of()).get("x"));
        assertEquals(NullState.NON_NULL, ExpressionNullability.atReceiver("x == null || x.ready()", "x", Map.of()).get("x"));
        assertEquals(NullState.UNKNOWN, ExpressionNullability.atReceiver("use(x == null ? x.read() : x.read());", "x", Map.of()).get("x"));
        assertTrue(ExpressionNullability.atReceiver("broken(", "x", Map.of()).isEmpty());
        assertTrue(ExpressionNullability.survivingReceivers("flag ? x.read() : y.read()").isEmpty());
        assertTrue(ExpressionNullability.survivingReceivers("() -> x.read()").isEmpty());
        assertTrue(ExpressionNullability.survivingReceivers("flag && x.ready()").isEmpty());
        assertTrue(ExpressionNullability.survivingReceivers("broken(").isEmpty());
        assertEquals(Map.of("x", NullState.NON_NULL), ExpressionNullability.survivingReceivers("x.read();"));
        assertEquals(NullState.UNKNOWN, ExpressionNullability.evaluate("broken(", Map.of(), Map.of()));
        assertEquals(NullState.NON_NULL, ExpressionNullability.evaluate("(String) (x)", Map.of("x", NullState.NON_NULL), Map.of()));
        assertEquals(NullState.UNKNOWN, ExpressionNullability.evaluate("x[0]", Map.of(), Map.of()));
    }
}
