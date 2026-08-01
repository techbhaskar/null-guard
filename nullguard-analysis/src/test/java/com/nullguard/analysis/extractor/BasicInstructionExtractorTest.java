package com.nullguard.analysis.extractor;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.NodeType;
import com.nullguard.analysis.ir.Instruction;
import com.nullguard.analysis.ir.MethodCallInstruction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

class BasicInstructionExtractorTest {

    @Test
    void testExtractFromReturn() {
        ControlFlowNode node = new ControlFlowNode("n1", NodeType.RETURN, "return service.process(req);", 1);
        LinkedHashMap<String, ControlFlowNode> nodes = new LinkedHashMap<>();
        nodes.put("n1", node);
        ControlFlowModel cfg = new ControlFlowModel("sig", nodes, new LinkedHashSet<>(), "n1", "n1");
        
        BasicInstructionExtractor extractor = new BasicInstructionExtractor();
        List<Instruction> instructions = extractor.extract(cfg);
        
        boolean found = false;
        for (Instruction inst : instructions) {
            if (inst instanceof MethodCallInstruction call) {
                if (call.methodCall().equals("service.process")) {
                    found = true;
                }
            }
        }
        assertTrue(found, "Should have found MethodCallInstruction for service.process in return statement. Instructions: " + instructions);
    }

    @Test
    void testMultipleCallsOnOneLine() {
        ControlFlowNode node = new ControlFlowNode("n1", NodeType.STATEMENT, "foo.bar(a.b());", 1);
        LinkedHashMap<String, ControlFlowNode> nodes = new LinkedHashMap<>();
        nodes.put("n1", node);
        ControlFlowModel cfg = new ControlFlowModel("sig", nodes, new LinkedHashSet<>(), "n1", "n1");
        
        BasicInstructionExtractor extractor = new BasicInstructionExtractor();
        List<Instruction> instructions = extractor.extract(cfg);
        
        long count = instructions.stream().filter(i -> i instanceof MethodCallInstruction).count();
        assertTrue(count >= 2, "Should have found at least 2 method calls. Found: " + count);
    }
}
