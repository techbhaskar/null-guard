package com.nullguard.core.builder;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.nullguard.core.cfg.ControlFlowModel;

public interface ControlFlowBuilder {
    ControlFlowModel build(MethodDeclaration method);
}
