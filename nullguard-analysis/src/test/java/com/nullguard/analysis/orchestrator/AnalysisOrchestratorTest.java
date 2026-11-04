package com.nullguard.analysis.orchestrator;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.contract.ContractModel;
import com.nullguard.analysis.engine.NullAnalysisModel;
import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.core.model.*;
import com.nullguard.core.parser.JavaParserAstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisOrchestratorTest {
    @TempDir Path root;
    private final AnalysisOrchestrator orchestrator = new AnalysisOrchestrator(new AnalysisConfig(10,.85,1,60,2));
    private Map<String,MethodModel> methods;
    private ProjectModel parse() throws Exception {
        Files.writeString(root.resolve("Example.java"),"""
            package sample;
            @interface GetMapping { String value(); }
            class ExampleController {
                @GetMapping("/first") int first(String value) { return new Service().shared(value); }
                @GetMapping("/second") int second(String value) { return new Service().shared(value); }
            }
            class Service {
                int shared(String value) { return value.length(); }
                int localUnsafe() { return new Box().size(); }
            }
            class Box { String value; int size() { return value.length(); } }
            """);
        ProjectModel project=new JavaParserAstParser().parse(root);
        methods=new TreeMap<>();
        project.getModules().values().forEach(m->m.getPackages().values().forEach(p->p.getClasses().values().forEach(c->
                c.getMethods().values().forEach(method->methods.put(MethodIds.of(p,c,method),method)))));
        return project;
    }
    private Map<String,Set<String>> edges() {
        return Map.of("sample.ExampleController#first(String)",Set.of("sample.Service#shared(String)"),
                "sample.ExampleController#second(String)",Set.of("sample.Service#shared(String)"));
    }

    @Test void integratedPassAttachesSummariesContractsLifecycleAndDistinctApiReach() throws Exception {
        ProjectModel project=parse();
        String original=Files.readString(root.resolve("Example.java"));
        orchestrator.analyze(project,edges());
        MethodModel shared=methods.get("sample.Service#shared(String)");
        assertInstanceOf(MethodSummary.class,shared.getMethodSummary().orElseThrow());
        assertTrue(((NullAnalysisModel)shared.getNullAnalysisModel().orElseThrow()).getUnguardedDereferences()>0);
        ContractModel contract=(ContractModel)shared.getContractModel().orElseThrow();
        assertTrue(contract.getIssues().stream().anyMatch(i->i.ruleId().equals("NG002")));
        var caller=(ContractModel)methods.get("sample.ExampleController#first(String)").getContractModel().orElseThrow();
        assertTrue(caller.getIssues().stream().anyMatch(i->i.ruleId().equals("NG004")),"Contracts must run after callee summaries are available");
        assertTrue(methods.get("sample.Service#localUnsafe()").getLifecycleIssues().stream().anyMatch(i->i.ruleId().equals("NG006")));
        assertEquals(2,shared.getReachData().orElseThrow().getCount(),"Reach counts must be applied back to the method model");
        var endpoints=orchestrator.getApiEndpointAnalyzer().getEndpoints();
        assertEquals(Set.of("/first","/second"),new HashSet<>(endpoints.stream().map(e->e.getPath()).toList()));
        assertTrue(endpoints.stream().allMatch(e->e.getHttpMethod().equals("GET") && e.getPropagationChain().contains("sample.Service#shared(String)")));
        assertTrue(orchestrator.getHotspotDetector().isHotspotCandidate(60,2));
        assertFalse(orchestrator.getHotspotDetector().isHotspotCandidate(60,1));
        assertTrue(shared.getAdjustedRiskModel().isEmpty(),"Risk propagation belongs to the scoring module");
        assertEquals(original,Files.readString(root.resolve("Example.java")));
    }

    @Test void repeatedAnalysisIsDeterministicWithoutDuplicateEndpointsOrReachCounts() throws Exception {
        ProjectModel project=parse();
        var api=orchestrator.getApiEndpointAnalyzer();
        var hotspot=orchestrator.getHotspotDetector();
        orchestrator.analyze(project,edges());
        var first=api.getEndpoints().stream().map(e->e.getEndpointId()+e.getPath()+e.getPropagationChain()).toList();
        int issueCount=((ContractModel)methods.get("sample.Service#shared(String)").getContractModel().orElseThrow()).getIssues().size();
        orchestrator.analyze(project,edges());
        assertSame(api,orchestrator.getApiEndpointAnalyzer());
        assertSame(hotspot,orchestrator.getHotspotDetector());
        assertEquals(first,api.getEndpoints().stream().map(e->e.getEndpointId()+e.getPath()+e.getPropagationChain()).toList());
        assertEquals(2,methods.get("sample.Service#shared(String)").getReachData().orElseThrow().getCount());
        assertEquals(issueCount,((ContractModel)methods.get("sample.Service#shared(String)").getContractModel().orElseThrow()).getIssues().size());
    }

    @Test void anEmptyProjectClearsPreviousApiStateAndCanBeAnalyzedRepeatedly() throws Exception {
        orchestrator.analyze(parse(),edges());
        assertFalse(orchestrator.getApiEndpointAnalyzer().getEndpoints().isEmpty());
        var empty=ProjectModel.builder().projectName("empty").build();
        orchestrator.analyze(empty,Map.of());
        orchestrator.analyze(empty,Map.of());
        assertTrue(orchestrator.getApiEndpointAnalyzer().getEndpoints().isEmpty());
        assertTrue(orchestrator.getApiEndpointAnalyzer().getReachTracker().getReachedBy().isEmpty());
        assertTrue(orchestrator.getApiEndpointAnalyzer().getReachTracker().getReachMap().isEmpty());
    }
}
