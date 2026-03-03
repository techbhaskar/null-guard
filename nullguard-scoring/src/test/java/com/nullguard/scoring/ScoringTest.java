package com.nullguard.scoring;

import com.nullguard.analysis.risk.RiskLevel;
import com.nullguard.analysis.risk.RiskModel;
import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.config.ScoringConfig;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.scoring.propagation.FixpointRiskPropagationEngine;
import com.nullguard.scoring.scoring.DefaultStabilityScorer;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ScoringTest {

    private ProjectModel createProject(Map<String, Integer> risks) {
        ClassModel.Builder cb = ClassModel.builder().className("TestClass");
        
        for (Map.Entry<String, Integer> e : risks.entrySet()) {
            MethodSummary summary = MethodSummary.builder()
                .intrinsicRiskProfile(new RiskModel(e.getValue(), RiskLevel.LOW))
                .build();
            MethodModel mm = MethodModel.builder()
                .methodName("testMethod")
                .signature(e.getKey()) 
                .build();
            
            try {
                java.lang.reflect.Field summaryField = mm.getClass().getDeclaredField("methodSummary");
                summaryField.setAccessible(true);
                summaryField.set(mm, summary);
            } catch (Exception ex) {
                // Ignore
            }
            cb.addMethod(mm);
        }

        PackageModel pkg = PackageModel.builder()
                .packageName("test.pkg")
                .addClass(cb.build())
                .build();

        ModuleModel mod = ModuleModel.builder()
                .moduleName("mod")
                .addPackage(pkg)
                .build();

        return ProjectModel.builder()
                .projectName("TestProject")
                .addModule(mod)
                .build();
    }

    private GlobalCallGraph createCallGraph(Map<String, Set<String>> outgoing, Set<String> external) {
        LinkedHashMap<String, LinkedHashSet<String>> out = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> in = new LinkedHashMap<>();
        
        for (Map.Entry<String, Set<String>> e : outgoing.entrySet()) {
            out.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            for (String callee : e.getValue()) {
                in.computeIfAbsent(callee, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }
        
        for (String caller : out.keySet()) {
            in.putIfAbsent(caller, new LinkedHashSet<>());
        }
        for (String e : external) {
            out.putIfAbsent(e, new LinkedHashSet<>());
            in.putIfAbsent(e, new LinkedHashSet<>());
        }
        
        return new GlobalCallGraph(out, in, external);
    }

    @Test
    public void testConvergenceLinearChain() {
        // A -> B -> C
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("B", 20);
        risks.put("C", 30);
        
        ProjectModel project = createProject(risks);
        
        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put("mod.test.pkg.TestClass#A", Set.of("mod.test.pkg.TestClass#B"));
        outgoing.put("mod.test.pkg.TestClass#B", Set.of("mod.test.pkg.TestClass#C"));
        
        GlobalCallGraph callGraph = createCallGraph(outgoing, Collections.emptySet());
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).build();
        
        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);
        
        assertNotNull(result);
        assertEquals(3, result.size());
        
        AdjustedRiskModel modelC = result.get("mod.test.pkg.TestClass#C");
        assertEquals(30.0, modelC.getIntrinsicRisk());
        assertEquals(0.0, modelC.getPropagatedRisk());
        assertEquals(30.0, modelC.getAdjustedRisk());
        
        AdjustedRiskModel modelB = result.get("mod.test.pkg.TestClass#B");
        assertEquals(20.0, modelB.getIntrinsicRisk());
        assertEquals(30.0 * 0.5, modelB.getPropagatedRisk());
        assertEquals(35.0, modelB.getAdjustedRisk());
        
        AdjustedRiskModel modelA = result.get("mod.test.pkg.TestClass#A");
        assertEquals(10.0, modelA.getIntrinsicRisk());
        assertEquals(35.0 * 0.5, modelA.getPropagatedRisk());
        assertEquals(27.5, modelA.getAdjustedRisk());
    }

    @Test
    public void testConvergenceCycle() {
        // A -> B -> A
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("B", 10);
        
        ProjectModel project = createProject(risks);
        
        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put("mod.test.pkg.TestClass#A", Set.of("mod.test.pkg.TestClass#B"));
        outgoing.put("mod.test.pkg.TestClass#B", Set.of("mod.test.pkg.TestClass#A"));
        
        GlobalCallGraph callGraph = createCallGraph(outgoing, Collections.emptySet());
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).convergenceThreshold(0.01).build();
        
        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);
        
        assertNotNull(result);
        
        AdjustedRiskModel modelA = result.get("mod.test.pkg.TestClass#A");
        AdjustedRiskModel modelB = result.get("mod.test.pkg.TestClass#B");
        
        // Summing geometric series: r = 10, decay = 0.5.
        // limit adjusted roughly 10 + 0.5*(10 + 0.5*...) = 20.
        assertTrue(Math.abs(modelA.getAdjustedRisk() - 20.0) < 0.1);
        assertTrue(Math.abs(modelB.getAdjustedRisk() - 20.0) < 0.1);
    }

    @Test
    public void testDeterministicOutputAcrossRuns() {
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("B", 10);
        
        ProjectModel project = createProject(risks);
        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put("mod.test.pkg.TestClass#A", Set.of("mod.test.pkg.TestClass#B"));
        outgoing.put("mod.test.pkg.TestClass#B", Set.of("mod.test.pkg.TestClass#A"));
        
        GlobalCallGraph callGraph = createCallGraph(outgoing, Collections.emptySet());
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).convergenceThreshold(0.01).build();
        
        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result1 = engine.propagate(project, callGraph, config);
        Map<String, AdjustedRiskModel> result2 = engine.propagate(project, callGraph, config);
        
        assertEquals(result1, result2);
    }

    @Test
    public void testExternalNodePenaltyEffect() {
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("Ext", 20);
        
        ProjectModel project = createProject(risks);
        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put("mod.test.pkg.TestClass#A", Set.of("mod.test.pkg.TestClass#Ext"));
        
        GlobalCallGraph callGraph = createCallGraph(outgoing, Set.of("mod.test.pkg.TestClass#Ext"));
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).externalPenaltyMultiplier(1.5).build();
        
        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);
        
        // Ext gets penalty?
        // Wait, penalty applies if `isExternal(M)`: newPropagated *= externalPenaltyMultiplier
        // Ext has 0 propagated because it calls nothing.
        // Ext -> newPropagated = 0.
        // A calls Ext. Ext's adjusted = 20 + 0 = 20. 
        // A's propagated = 20 * 0.5 = 10. A is not external. A's adjusted = 10 + 10 = 20.
        
        AdjustedRiskModel modelA = result.get("mod.test.pkg.TestClass#A");
        assertEquals(20.0, modelA.getAdjustedRisk());
    }
    
    @Test
    public void testExternalNodePenaltyEffectOnItself() {
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("Ext", 20);
        
        ProjectModel project = createProject(risks);
        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put("mod.test.pkg.TestClass#Ext", Set.of("mod.test.pkg.TestClass#A"));
        
        GlobalCallGraph callGraph = createCallGraph(outgoing, Set.of("mod.test.pkg.TestClass#Ext"));
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).externalPenaltyMultiplier(1.5).build();
        
        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);
        
        // Ext calls A. A's adj = 10. Ext Prop = 10 * 0.5 = 5.
        // Ext is external -> prop * 1.5 = 7.5.
        // Ext Adj = 20 + 7.5 = 27.5.
        AdjustedRiskModel modelExt = result.get("mod.test.pkg.TestClass#Ext");
        assertEquals(27.5, modelExt.getAdjustedRisk());
    }

    @Test
    public void testGradeBoundaryCorrectness() {
        DefaultStabilityScorer scorer = new DefaultStabilityScorer();
        Map<String, AdjustedRiskModel> finalModels = new HashMap<>();
        
        // Avg Object
        finalModels.put("m1", new AdjustedRiskModel(0, 0, 10.0, com.nullguard.scoring.model.RiskLevel.LOW)); // stability 90 -> A
        ProjectRiskSummary r1 = scorer.score(finalModels, createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("A", r1.getGrade());
        
        finalModels.put("m1", new AdjustedRiskModel(0, 0, 20.0, com.nullguard.scoring.model.RiskLevel.LOW)); // stability 80 -> B
        ProjectRiskSummary r2 = scorer.score(finalModels, createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("B", r2.getGrade());
        
        finalModels.put("m1", new AdjustedRiskModel(0, 0, 30.0, com.nullguard.scoring.model.RiskLevel.LOW)); // stability 70 -> C
        ProjectRiskSummary r3 = scorer.score(finalModels, createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("C", r3.getGrade());
        
        finalModels.put("m1", new AdjustedRiskModel(0, 0, 40.0, com.nullguard.scoring.model.RiskLevel.MEDIUM)); // stability 60 -> D
        ProjectRiskSummary r4 = scorer.score(finalModels, createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("D", r4.getGrade());
        
        finalModels.put("m1", new AdjustedRiskModel(0, 0, 50.0, com.nullguard.scoring.model.RiskLevel.MEDIUM)); // stability 50 -> F
        ProjectRiskSummary r5 = scorer.score(finalModels, createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("F", r5.getGrade());
    }
}
