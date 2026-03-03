package com.nullguard.analysis.engine;

import com.nullguard.analysis.lattice.NullState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NullAnalysisModel {
    private final String methodSignature;
    private final Map<String, Map<String, NullState>> inStates;
    private final Map<String, Map<String, NullState>> outStates;
    private final int unguardedDereferences;
    private final boolean nullableReturn;
    private final boolean propagatesNullFromCallee;

    public NullAnalysisModel(String methodSignature,
                             LinkedHashMap<String, Map<String, NullState>> inStates,
                             LinkedHashMap<String, Map<String, NullState>> outStates,
                             int unguardedDereferences,
                             boolean nullableReturn,
                             boolean propagatesNullFromCallee) {
        this.methodSignature = methodSignature;
        this.inStates = deepCopy(inStates);
        this.outStates = deepCopy(outStates);
        this.unguardedDereferences = unguardedDereferences;
        this.nullableReturn = nullableReturn;
        this.propagatesNullFromCallee = propagatesNullFromCallee;
    }

    private Map<String, Map<String, NullState>> deepCopy(Map<String, Map<String, NullState>> original) {
        Map<String, Map<String, NullState>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, NullState>> entry : original.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public String getMethodSignature() { return methodSignature; }
    public Map<String, Map<String, NullState>> getInStates() { return inStates; }
    public Map<String, Map<String, NullState>> getOutStates() { return outStates; }
    public int getUnguardedDereferences() { return unguardedDereferences; }
    public boolean isNullableReturn() { return nullableReturn; }
    public boolean isPropagatesNullFromCallee() { return propagatesNullFromCallee; }
}
