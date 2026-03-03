package com.nullguard.analysis.model;

import java.util.List;
import java.util.Collections;

public class APIFlowTrace {
    private final List<String> path;

    public APIFlowTrace(List<String> path) {
        this.path = Collections.unmodifiableList(path);
    }

    public List<String> getPath() { return path; }
}
