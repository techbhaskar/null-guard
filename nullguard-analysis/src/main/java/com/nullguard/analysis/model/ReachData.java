package com.nullguard.analysis.model;

import java.util.List;
import java.util.Collections;

public class ReachData {
    private final int count;
    private final List<String> reachableApis;
    private final boolean candidateFlag;

    public ReachData(int count, List<String> reachableApis, boolean candidateFlag) {
        this.count = count;
        this.reachableApis = Collections.unmodifiableList(reachableApis);
        this.candidateFlag = candidateFlag;
    }

    public int getCount() { return count; }
    public List<String> getReachableApis() { return reachableApis; }
    public boolean isCandidateFlag() { return candidateFlag; }
}
