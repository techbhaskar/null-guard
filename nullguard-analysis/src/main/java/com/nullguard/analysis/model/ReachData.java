package com.nullguard.analysis.model;

import java.util.List;
import java.util.Collections;

/**
 * How many API entry points reach a method.
 *
 * <p>Implements {@link com.nullguard.core.spi.MethodAnalysisArtifacts.ReachCountSource} so the
 * hotspot detector can read the count directly. It previously reflected on {@code getCount()}
 * while already importing this class and never referencing it.
 */
public class ReachData implements com.nullguard.core.spi.MethodAnalysisArtifacts.ReachCountSource {
    private final int count;
    private final List<String> reachableApis;
    private final boolean candidateFlag;

    public ReachData(int count, List<String> reachableApis, boolean candidateFlag) {
        this.count = count;
        this.reachableApis = Collections.unmodifiableList(reachableApis);
        this.candidateFlag = candidateFlag;
    }

    @Override public int getCount() { return count; }
    public List<String> getReachableApis() { return reachableApis; }
    public boolean isCandidateFlag() { return candidateFlag; }
}
