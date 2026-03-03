package com.nullguard.analysis.extractor;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.analysis.ir.*;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

public final class BasicInstructionExtractor implements InstructionExtractor {
    @Override
    public List<Instruction> extract(ControlFlowModel cfg) {
        List<Instruction> instructions = new ArrayList<>();
        int instrIndex = 0;
        
        for (ControlFlowNode node : cfg.getNodes().values()) {
            String src = node.getSourceCode();
            String cfgId = node.getId();
            String methodSig = cfg.getMethodSignature();
            String id = methodSig + "_" + cfgId + "_" + (instrIndex++);
            int line = node.getLineNumber();
            
            switch (node.getType()) {
                case STATEMENT:
                    if (src.contains("=") && !src.contains("==")) {
                        instructions.add(new AssignmentInstruction(id, cfgId, line, "target", "source"));
                    } else if (src.contains(".")) {
                        instructions.add(new DereferenceInstruction(id, cfgId, line, "var"));
                    } else if (src.contains("(")) {
                        instructions.add(new MethodCallInstruction(id, cfgId, line, "methodCall"));
                    }
                    break;
                case RETURN:
                    instructions.add(new ReturnInstruction(id, cfgId, line, "retVal"));
                    break;
                case CONDITION:
                    instructions.add(new ConditionalInstruction(id, cfgId, line, "cond"));
                    break;
                case THROW:
                    instructions.add(new ThrowInstruction(id, cfgId, line, "exception"));
                    break;
                default:
                    break;
            }
        }
        return Collections.unmodifiableList(instructions);
    }
}
