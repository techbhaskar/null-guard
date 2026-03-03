package com.nullguard.analysis.engine;
import com.nullguard.core.cfg.ControlFlowModel;
public interface NullStateAnalyzer {
    NullAnalysisModel analyze(ControlFlowModel cfg);
}
