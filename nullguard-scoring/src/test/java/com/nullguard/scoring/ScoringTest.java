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

    /**
     * Package = "test.pkg", class = "TestClass".
     * Canonical method ID used by FixpointRiskPropagationEngine:
     *   packageName.className#signature  →  "test.pkg.TestClass#<sig>"
     */
    private static final String PKG   = "test.pkg";
    private static final String CLS   = "TestClass";

    private static String methodId(String sig) {
        return PKG + "." + CLS + "#" + sig;
    }

    private ProjectModel createProject(Map<String, Integer> risks) {
        ClassModel.Builder cb = ClassModel.builder().className(CLS);

        for (Map.Entry<String, Integer> e : risks.entrySet()) {
            MethodSummary summary = MethodSummary.builder()
                .intrinsicRiskProfile(new RiskModel(e.getValue(), RiskLevel.LOW))
                .build();
            MethodModel mm = MethodModel.builder()
                .methodName("testMethod")
                .signature(e.getKey())
                .methodSummary(summary)   // use the new builder setter — no reflection needed
                .build();
            cb.addMethod(mm);
        }

        PackageModel pkg = PackageModel.builder()
                .packageName(PKG)
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
        LinkedHashMap<String, LinkedHashSet<String>> in  = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> e : outgoing.entrySet()) {
            out.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            for (String callee : e.getValue()) {
                in.computeIfAbsent(callee, k -> new LinkedHashSet<>()).add(e.getKey());
            }
        }

        for (String caller : out.keySet()) {
            in.putIfAbsent(caller, new LinkedHashSet<>());
        }
        LinkedHashSet<String> externalSet = new LinkedHashSet<>(external);
        for (String e : external) {
            out.putIfAbsent(e, new LinkedHashSet<>());
            in.putIfAbsent(e, new LinkedHashSet<>());
        }

        return new GlobalCallGraph(out, in, externalSet);
    }

    @Test
    public void testConvergenceLinearChain() {
        // A -> B -> C   (IDs use canonical packageName.ClassName#sig format)
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("B", 20);
        risks.put("C", 30);

        ProjectModel project = createProject(risks);

        String idA = methodId("A");
        String idB = methodId("B");
        String idC = methodId("C");

        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put(idA, Set.of(idB));
        outgoing.put(idB, Set.of(idC));

        GlobalCallGraph callGraph = createCallGraph(outgoing, Collections.emptySet());
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).build();

        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);

        assertNotNull(result);
        assertEquals(3, result.size());

        AdjustedRiskModel modelC = result.get(idC);
        assertEquals(30.0, modelC.getIntrinsicRisk());
        assertEquals(0.0,  modelC.getPropagatedRisk());
        assertEquals(30.0, modelC.getAdjustedRisk());

        AdjustedRiskModel modelB = result.get(idB);
        assertEquals(20.0,        modelB.getIntrinsicRisk());
        assertEquals(30.0 * 0.5,  modelB.getPropagatedRisk());
        assertEquals(35.0,        modelB.getAdjustedRisk());

        AdjustedRiskModel modelA = result.get(idA);
        assertEquals(10.0,        modelA.getIntrinsicRisk());
        assertEquals(35.0 * 0.5,  modelA.getPropagatedRisk());
        assertEquals(27.5,        modelA.getAdjustedRisk());
    }

    @Test
    public void testConvergenceCycle() {
        // A -> B -> A
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("B", 10);

        ProjectModel project = createProject(risks);

        String idA = methodId("A");
        String idB = methodId("B");

        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put(idA, Set.of(idB));
        outgoing.put(idB, Set.of(idA));

        GlobalCallGraph callGraph = createCallGraph(outgoing, Collections.emptySet());
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).convergenceThreshold(0.01).build();

        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);

        assertNotNull(result);

        AdjustedRiskModel modelA = result.get(idA);
        AdjustedRiskModel modelB = result.get(idB);

        // Summing geometric series: r = 10, decay = 0.5 → converges near 20.
        assertTrue(Math.abs(modelA.getAdjustedRisk() - 20.0) < 0.1);
        assertTrue(Math.abs(modelB.getAdjustedRisk() - 20.0) < 0.1);
    }

    @Test
    public void testDeterministicOutputAcrossRuns() {
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A", 10);
        risks.put("B", 10);

        ProjectModel project = createProject(risks);

        String idA = methodId("A");
        String idB = methodId("B");

        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put(idA, Set.of(idB));
        outgoing.put(idB, Set.of(idA));

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
        risks.put("A",   10);
        risks.put("Ext", 20);

        ProjectModel project = createProject(risks);

        String idA   = methodId("A");
        String idExt = methodId("Ext");

        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put(idA, Set.of(idExt));

        GlobalCallGraph callGraph = createCallGraph(outgoing, Set.of(idExt));
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).externalPenaltyMultiplier(1.5).build();

        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);

        // Ext has 0 propagated (calls nothing). Ext adj = 20.
        // A's propagated = 20 * 0.5 = 10. A adj = 10 + 10 = 20.
        AdjustedRiskModel modelA = result.get(idA);
        assertEquals(20.0, modelA.getAdjustedRisk());
    }

    @Test
    public void testExternalNodePenaltyEffectOnItself() {
        Map<String, Integer> risks = new HashMap<>();
        risks.put("A",   10);
        risks.put("Ext", 20);

        ProjectModel project = createProject(risks);

        String idA   = methodId("A");
        String idExt = methodId("Ext");

        Map<String, Set<String>> outgoing = new HashMap<>();
        outgoing.put(idExt, Set.of(idA));

        GlobalCallGraph callGraph = createCallGraph(outgoing, Set.of(idExt));
        ScoringConfig config = ScoringConfig.builder().decayFactor(0.5).externalPenaltyMultiplier(1.5).build();

        FixpointRiskPropagationEngine engine = new FixpointRiskPropagationEngine();
        Map<String, AdjustedRiskModel> result = engine.propagate(project, callGraph, config);

        // Ext calls A. A adj = 10. Ext prop = 10 * 0.5 = 5.
        // Ext is external → prop * 1.5 = 7.5. Ext adj = 20 + 7.5 = 27.5.
        AdjustedRiskModel modelExt = result.get(idExt);
        assertEquals(27.5, modelExt.getAdjustedRisk());
    }

    @Test
    public void testGradeBoundaryCorrectness() {
        DefaultStabilityScorer scorer = new DefaultStabilityScorer();
        Map<String, AdjustedRiskModel> finalModels = new HashMap<>();

        finalModels.put("m1", new AdjustedRiskModel(0, 0, 10.0, com.nullguard.scoring.model.RiskLevel.LOW));
        ProjectRiskSummary r1 = scorer.score(finalModels,
                createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("A", r1.getGrade());   // stability = 90

        finalModels.put("m1", new AdjustedRiskModel(0, 0, 20.0, com.nullguard.scoring.model.RiskLevel.LOW));
        ProjectRiskSummary r2 = scorer.score(finalModels,
                createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("B", r2.getGrade());   // stability = 80

        finalModels.put("m1", new AdjustedRiskModel(0, 0, 30.0, com.nullguard.scoring.model.RiskLevel.LOW));
        ProjectRiskSummary r3 = scorer.score(finalModels,
                createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("C", r3.getGrade());   // stability = 70

        finalModels.put("m1", new AdjustedRiskModel(0, 0, 40.0, com.nullguard.scoring.model.RiskLevel.MEDIUM));
        ProjectRiskSummary r4 = scorer.score(finalModels,
                createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("D", r4.getGrade());   // stability = 60

        finalModels.put("m1", new AdjustedRiskModel(0, 0, 50.0, com.nullguard.scoring.model.RiskLevel.MEDIUM));
        ProjectRiskSummary r5 = scorer.score(finalModels,
                createCallGraph(new HashMap<>(), Set.of()), ScoringConfig.builder().build());
        assertEquals("F", r5.getGrade());   // stability = 50
    }
}
