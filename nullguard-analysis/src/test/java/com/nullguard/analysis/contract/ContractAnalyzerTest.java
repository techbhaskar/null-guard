package com.nullguard.analysis.contract;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.summary.MethodSummaryEngine;
import com.nullguard.core.model.*;
import com.nullguard.core.parser.JavaParserAstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractAnalyzerTest {
    @TempDir Path root;
    private final AnalysisConfig config = new AnalysisConfig(10,.85,1,60,1);
    private final ContractAnalyzer analyzer = new ContractAnalyzer(config);
    private Map<String,MethodModel> methods;
    private ProjectModel project(String source, boolean summaries) throws Exception {
        Path file=root.resolve("Example.java");
        Files.writeString(file,source);
        ProjectModel project=new JavaParserAstParser().parse(root);
        if (summaries) new MethodSummaryEngine(config).run(project);
        methods=new LinkedHashMap<>();
        project.getModules().values().forEach(m->m.getPackages().values().forEach(p->p.getClasses().values().forEach(c->
                c.getMethods().values().forEach(method->methods.put(c.getClassName()+"#"+method.getSignature(),method)))));
        return project;
    }
    private ContractModel contract(String name) {
        return (ContractModel)Objects.requireNonNull(methods.get(name),name).getContractModel().orElseThrow();
    }
    private boolean has(String name,String rule) { return contract(name).getIssues().stream().anyMatch(i->i.ruleId().equals(rule)); }

    @Test void returnAndParameterViolationsHaveIndependentCappedPenalties() throws Exception {
        var p=project("""
            @interface NonNull {}
            class Example {
                @NonNull String badReturn() { return null; }
                @NonNull String goodReturn() { return "ok"; }
                int unchecked(String value) { return value.length(); }
                int guarded(String value) { if (value == null) return 0; return value.length(); }
                @NonNull String both(String a, String b) { a.length(); b.length(); return null; }
            }
            """,true);
        analyzer.analyze(p);
        assertTrue(has("Example#badReturn()","NG003"));
        assertEquals(10,contract("Example#badReturn()").getContractPenalty());
        assertEquals(0,contract("Example#goodReturn()").getContractPenalty());
        assertTrue(has("Example#unchecked(String)","NG002"));
        assertEquals(5,contract("Example#unchecked(String)").getContractPenalty());
        assertEquals(0,contract("Example#guarded(String)").getContractPenalty());
        assertEquals(15,contract("Example#both(String, String)").getContractPenalty());
        assertTrue(contract("Example#both(String, String)").isReturnContractViolation());
        assertTrue(contract("Example#both(String, String)").isParameterContractViolation());
        assertTrue(contract("Example#both(String, String)").getIssues().stream().allMatch(i->i.line()>0));
    }

    @Test void callerArgumentsRespectGuardsDeclaredContractsAndPrimitiveBoxing() throws Exception {
        var p=project("""
            @interface NonNull {}
            class Example {
                static void needs(@NonNull String value) {}
                static void boxed(@NonNull Integer number) {}
                void bad() { needs(null); needs(null); }
                void good() { needs("ready"); }
                void guarded(String value) { if (value != null) needs(value); }
                void unchecked(String value) { needs(value); }
                void primitive(int value) { boxed(value); }
                void boxedUnknown(Integer value) { boxed(value); }
                void expressionGuard(String value) { boolean result = value == null || accept(value); }
                static boolean accept(@NonNull String value) { return true; }
            }
            """,true);
        analyzer.analyze(p,Map.of());
        for (String name:List.of("Example#bad()","Example#unchecked(String)","Example#boxedUnknown(Integer)")) assertTrue(has(name,"NG004"),name);
        for (String name:List.of("Example#good()","Example#guarded(String)","Example#primitive(int)","Example#expressionGuard(String)")) assertFalse(has(name,"NG004"),name);
        assertEquals(5,contract("Example#bad()").getContractPenalty(),"Repeated bad arguments must not multiply the method penalty");
    }

    @Test void constructorsIncludeThisSuperAndValidatedParameters() throws Exception {
        var p=project("""
            @interface NonNull {}
            class Required { Required(@NonNull String value) {} }
            class Checked { Checked(String value) { java.util.Objects.requireNonNull(value); } }
            class Child extends Required {
                Child() { super(null); }
                Child(String value) { this(); }
            }
            class Client {
                void bad() { new Required(null); new Checked(null); }
                void good() { new Required("ready"); new Checked("ready"); }
            }
            """,true);
        analyzer.analyze(p);
        assertTrue(has("Client#bad()","NG004"));
        assertEquals(2,contract("Client#bad()").getIssues().size());
        assertFalse(has("Client#good()","NG004"));
        assertTrue(has("Child#<init>()","NG004"));
        assertFalse(has("Child#<init>(String)","NG004"));
    }

    @Test void missingSummariesAreSkippedAndViolationsDoNotLeakBetweenRuns() throws Exception {
        var p=project("class Example { int bad(String value) { return value.length(); } }",true);
        analyzer.analyze(p);
        assertEquals(1,analyzer.getViolationCount());
        var snapshot=analyzer.getViolations();
        assertThrows(UnsupportedOperationException.class,snapshot::clear);
        var violation=snapshot.get(0);
        assertTrue(violation.methodId().endsWith("#bad(String)"));
        assertFalse(violation.returnViolation()); assertTrue(violation.parameterViolation()); assertEquals(5,violation.penalty());
        var noSummaries=project("class Other { String value() { return null; } }",false);
        analyzer.analyze(noSummaries);
        assertEquals(0,analyzer.getViolationCount());
        assertEquals(1,snapshot.size());
        assertTrue(methods.get("Other#value()").getContractModel().isEmpty());
    }

    @Test void resolvedCallsUseTheSelectedOverloadAndIgnoreExternalTargets() throws Exception {
        var p=project("""
            @interface NonNull {}
            class Example {
                static void use(@NonNull String value) {}
                static void use(Integer value) {}
                void nullableOverload(Integer value) { use(value); }
                void requiredOverload(String value) { use(value); }
                void external() { System.out.println((Object)null); }
            }
            """,true);
        analyzer.analyze(p);
        assertFalse(has("Example#nullableOverload(Integer)","NG004"));
        assertTrue(has("Example#requiredOverload(String)","NG004"));
        assertEquals(0,contract("Example#external()").getContractPenalty());
    }
}
