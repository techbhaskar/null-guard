package com.nullguard.analysis.engine;

import com.nullguard.analysis.lattice.NullState;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NullAnalysisModelTest {
    @Test void analysisResultsAreImmutableSnapshotsOfBothStateMaps() {
        var input = new LinkedHashMap<String, Map<String, NullState>>();
        var output = new LinkedHashMap<String, Map<String, NullState>>();
        var variables = new LinkedHashMap<String, NullState>();
        variables.put("x", NullState.NULL);
        input.put("entry",variables);
        output.put("exit",new LinkedHashMap<>(Map.of("x",NullState.NON_NULL)));
        var result = new NullAnalysisModel("Example#read()",input,output,2,true,true);
        variables.put("x",NullState.NON_NULL);
        input.clear(); output.get("exit").clear();
        assertEquals(NullState.NULL,result.getInStates().get("entry").get("x"));
        assertEquals(NullState.NON_NULL,result.getOutStates().get("exit").get("x"));
        assertThrows(UnsupportedOperationException.class,()->result.getInStates().clear());
        assertThrows(UnsupportedOperationException.class,()->result.getOutStates().get("exit").clear());
        assertEquals("Example#read()",result.getMethodSignature());
        assertEquals(2,result.getUnguardedDereferences());
        assertTrue(result.isNullableReturn());
        assertTrue(result.isPropagatesNullFromCallee());
    }
}
