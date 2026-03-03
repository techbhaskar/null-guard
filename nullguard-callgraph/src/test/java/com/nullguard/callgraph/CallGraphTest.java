package com.nullguard.callgraph;

import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.NodeType;
import com.nullguard.callgraph.builder.BasicCallGraphBuilder;
import com.nullguard.callgraph.model.GlobalCallGraph;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

class CallGraphTest {

    @Test
    void testEmptyProject() {
        ProjectModel project = ProjectModel.builder().projectName("Empty").build();
        BasicCallGraphBuilder builder = new BasicCallGraphBuilder();
        GlobalCallGraph graph = builder.build(project);
        
        assertTrue(graph.getOutgoing().isEmpty());
    }
    
    @Test
    void testMethodResolution() {
        ControlFlowNode callNode = new ControlFlowNode("node1", NodeType.STATEMENT, "foo()", 1);
        LinkedHashMap<String, ControlFlowNode> nodesMap = new LinkedHashMap<>();
        nodesMap.put("node1", callNode);
        
        ControlFlowModel cfm = new ControlFlowModel("testSig", nodesMap, new LinkedHashSet<>(), "entry", "exit");
        MethodModel method = MethodModel.builder()
            .methodName("testSig")
            .signature("testSig()")
            .controlFlowModel(cfm)
            .build();
            
        ClassModel cls = ClassModel.builder().className("TestClass").addMethod(method).build();
        PackageModel pkg = PackageModel.builder().packageName("com.test").addClass(cls).build();
        ModuleModel mod = ModuleModel.builder().moduleName("modA").addPackage(pkg).build();
        ProjectModel project = ProjectModel.builder().projectName("testProj").addModule(mod).build();
        
        BasicCallGraphBuilder builder = new BasicCallGraphBuilder();
        GlobalCallGraph graph = builder.build(project);
        
        String methodId = "com.test.TestClass#testSig()";
        assertTrue(graph.getOutgoing().containsKey(methodId));
        assertFalse(graph.getOutgoing().get(methodId).isEmpty());
        assertTrue(graph.getExternalNodes().contains("ext#methodCall"));
    }
}
