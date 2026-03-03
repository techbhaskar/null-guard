package com.nullguard.core.builder;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.nullguard.core.cfg.ControlFlowModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class BasicControlFlowBuilderTest {

    @Test
    void testCfgGeneration() {
        String code = "class Test { void foo() { int a = 1; return; } }";
        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(code).getResult().get();
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();

        BasicControlFlowBuilder builder = new BasicControlFlowBuilder();
        ControlFlowModel model = builder.build(method);

        assertNotNull(model);
        assertEquals(4, model.getNodes().size()); 
        assertEquals(3, model.getEdges().size());
        
        List<String> orderedNodeIds = model.getNodes().keySet().stream().collect(Collectors.toList());
        assertEquals("foo_0_0", orderedNodeIds.get(0)); 
        assertTrue(orderedNodeIds.contains("foo_1_1")); 
        assertTrue(orderedNodeIds.contains("foo_1_2") || orderedNodeIds.contains("foo_0_2")); 
        assertTrue(orderedNodeIds.contains("foo_-1_0")); 
        assertEquals(4, orderedNodeIds.size());
    }

    @Test
    void testImmutability() {
        BasicControlFlowBuilder builder = new BasicControlFlowBuilder();
        MethodDeclaration method = new MethodDeclaration();
        method.setName("emptyMethod");
        method.createBody();
        
        ControlFlowModel model = builder.build(method);
        
        assertThrows(UnsupportedOperationException.class, () -> {
            model.getNodes().put("foo", null);
        });

        assertThrows(UnsupportedOperationException.class, () -> {
            model.getEdges().clear();
        });
    }
}
