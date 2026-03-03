package com.nullguard.analysis.engine;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.analysis.extractor.InstructionExtractor;
import com.nullguard.analysis.ir.*;
import com.nullguard.analysis.lattice.NullState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ForwardDataFlowAnalyzer implements NullStateAnalyzer {
    
    private final InstructionExtractor extractor;

    public ForwardDataFlowAnalyzer(InstructionExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public NullAnalysisModel analyze(ControlFlowModel cfg) {
        List<Instruction> instructions = extractor.extract(cfg);
        
        LinkedHashMap<String, Map<String, NullState>> inState = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, NullState>> outState = new LinkedHashMap<>();
        
        for (Instruction inst : instructions) {
            inState.put(inst.id(), new LinkedHashMap<>());
            outState.put(inst.id(), new LinkedHashMap<>());
        }

        int dereferenceCount = 0;
        boolean returnsNull = false;

        boolean changed = true;
        while (changed) {
            changed = false;
            
            for (int i = 0; i < instructions.size(); i++) {
                Instruction inst = instructions.get(i);
                Map<String, NullState> currentOut = outState.get(inst.id());
                
                Map<String, NullState> newIn = new LinkedHashMap<>();
                if (i > 0) {
                    Instruction prev = instructions.get(i - 1);
                    newIn.putAll(outState.get(prev.id()));
                }
                
                Map<String, NullState> newOut = new LinkedHashMap<>(newIn);
                
                if (inst instanceof AssignmentInstruction assign) {
                    newOut.put(assign.target(), NullState.UNKNOWN);
                } else if (inst instanceof DereferenceInstruction) {
                    // tracked globally outside the fixpoint loop
                } else if (inst instanceof ReturnInstruction) {
                    // tracked globally outside the fixpoint loop
                }

                if (!currentOut.equals(newOut)) {
                    outState.put(inst.id(), newOut);
                    changed = true;
                }
                inState.put(inst.id(), newIn);
            }
        }
        
        for (Instruction inst : instructions) {
            if (inst instanceof DereferenceInstruction) {
                dereferenceCount++;
            }
            if (inst instanceof ReturnInstruction) {
                returnsNull = true;
            }
        }

        return new NullAnalysisModel(
                cfg.getMethodSignature(),
                inState,
                outState,
                dereferenceCount,
                returnsNull,
                true 
        );
    }
}
