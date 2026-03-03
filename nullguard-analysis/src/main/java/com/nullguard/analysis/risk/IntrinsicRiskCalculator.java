package com.nullguard.analysis.risk;
import com.nullguard.analysis.engine.NullAnalysisModel;
public final class IntrinsicRiskCalculator {
    public RiskModel calculate(NullAnalysisModel model) {
        int score = 0;
        score += model.getUnguardedDereferences() * 20;
        if (model.isNullableReturn()) score += 10;
        if (model.isPropagatesNullFromCallee()) score += 15;
        
        score = Math.min(100, Math.max(0, score));
        
        RiskLevel level;
        if (score < 20) level = RiskLevel.LOW;
        else if (score < 50) level = RiskLevel.MEDIUM;
        else if (score < 80) level = RiskLevel.HIGH;
        else level = RiskLevel.CRITICAL;
        
        return new RiskModel(score, level);
    }
}
