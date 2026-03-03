package com.nullguard.suggestions.engine;

import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.ranking.SuggestionRanker;
import com.nullguard.suggestions.rules.BlastRadiusRefactorRule;
import com.nullguard.suggestions.rules.ExternalValidationRule;
import com.nullguard.suggestions.rules.NullGuardRule;
import com.nullguard.suggestions.rules.ReturnContractRule;
import com.nullguard.suggestions.rules.RiskIsolationRule;
import com.nullguard.suggestions.rules.SuggestionRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DefaultSuggestionEngine implements SuggestionEngine {
    
    @Override
    public List<Suggestion> generate(ProjectModel project, Map<String, AdjustedRiskModel> riskMap, GlobalCallGraph callGraph) {
        Map<String, MethodSummary> summaries = new HashMap<>();
        
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel m : cls.getMethods().values()) {
                        String methodId = mod.getModuleName() + "." + pkg.getPackageName() + "." + cls.getClassName() + "#" + m.getSignature();
                        m.getMethodSummary().ifPresent(obj -> {
                            if (obj instanceof MethodSummary) {
                                summaries.put(methodId, (MethodSummary) obj);
                            }
                        });
                    }
                }
            }
        }
        
        List<SuggestionRule> rules = Arrays.asList(
                new NullGuardRule(),
                new ReturnContractRule(),
                new ExternalValidationRule(callGraph),
                new BlastRadiusRefactorRule(),
                new RiskIsolationRule(callGraph)
        );
        
        List<Suggestion> rawSuggestions = new ArrayList<>();
        
        // Iterate method IDs deterministically (sort riskMap keys)
        List<String> sortedMethodIds = new ArrayList<>(riskMap.keySet());
        Collections.sort(sortedMethodIds);
        
        for (String methodId : sortedMethodIds) {
            AdjustedRiskModel riskModel = riskMap.get(methodId);
            MethodSummary summary = summaries.get(methodId);
            if (summary == null) continue;
            
            for (SuggestionRule rule : rules) {
                Optional<Suggestion> opt = rule.evaluate(methodId, summary, riskModel);
                opt.ifPresent(rawSuggestions::add);
            }
        }
        
        SuggestionRanker ranker = new SuggestionRanker();
        return ranker.rank(rawSuggestions);
    }
}
