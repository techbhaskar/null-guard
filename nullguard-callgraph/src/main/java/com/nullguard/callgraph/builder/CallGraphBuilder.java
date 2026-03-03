package com.nullguard.callgraph.builder;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.callgraph.model.GlobalCallGraph;
public interface CallGraphBuilder {
    GlobalCallGraph build(ProjectModel project);
}
