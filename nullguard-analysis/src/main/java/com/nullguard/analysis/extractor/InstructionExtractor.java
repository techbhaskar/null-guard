package com.nullguard.analysis.extractor;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.analysis.ir.Instruction;
import java.util.List;
public interface InstructionExtractor {
    List<Instruction> extract(ControlFlowModel cfg);
}
