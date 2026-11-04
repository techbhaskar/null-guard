package com.nullguard.analysis.engine;

import com.nullguard.analysis.lattice.NullState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpressionNullabilityTest {
    @Test void knownValuesAndCallSummariesHaveExplicitNullability() {
        Map<String, NullState> state = Map.of("x",NullState.NULL,"this.value",NullState.NON_NULL);
        assertEquals(NullState.NON_NULL,ExpressionNullability.evaluate("this.value",state,Map.of()));
        assertEquals(NullState.UNKNOWN,ExpressionNullability.evaluate("other.value",state,Map.of()));
        assertEquals(NullState.NON_NULL,ExpressionNullability.evaluate("x == null ? new Object() : x",state,Map.of()));
        assertEquals(NullState.NULL,ExpressionNullability.evaluate("service.load()",state,Map.of("service.load/0",NullState.NULL)));
        for (String source : List.of("Object.class","1","new Object()","new int[1]","this","1 + 1","!true"))
            assertEquals(NullState.NON_NULL,ExpressionNullability.evaluate(source,state,Map.of()),source);
    }
    @Test void writesInvalidateReceiverFactsAndUnknownStatementsInvalidateEveryName() {
        assertEquals(Set.of("x"),ExpressionNullability.assignedNames("int x = 1;"));
        assertEquals(Set.of("x"),ExpressionNullability.assignedNames("x++;"));
        assertEquals(Set.of("this.value"),ExpressionNullability.assignedNames("this.value = null;"));
        assertTrue(ExpressionNullability.assignedNames("broken(").contains("anyReceiver"));
        assertFalse(ExpressionNullability.assignedNames("broken(").iterator().hasNext());
        assertEquals(0,ExpressionNullability.assignedNames("broken(").size());
        assertEquals("",ExpressionNullability.assignmentRhs("String x;"));
        assertEquals("",ExpressionNullability.assignmentRhs("return;"));
        assertEquals("value",ExpressionNullability.assignmentRhs("x = value;"));
        assertEquals("new Object()",ExpressionNullability.assignmentRhs("Object x = new Object();"));
    }
}
