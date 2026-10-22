package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrectnessCompletenessTest {
    @TempDir Path root;

    private FinalAnalysisResult run(String source) throws Exception {
        Files.writeString(root.resolve("Example.java"), source);
        return new EngineBootstrap(NullGuardConfig.defaults().outputDirectory(root.resolve("out")).build()).run(root);
    }

    @Test void manualConstructorNullsAreCheckedWithoutRejectingSafeOrNullableArguments() throws Exception {
        var result = run("""
                import org.springframework.stereotype.Service;
                import org.springframework.lang.Nullable;
                @Service class Bean {
                    final String dependency;
                    Bean(String dependency) { this.dependency = dependency; }
                    int work() { return dependency.length(); }
                }
                class OptionalBean {
                    OptionalBean(@Nullable String dependency) { if (dependency != null) dependency.length(); }
                }
                class Client {
                    int bad() { return new Bean(null).work(); }
                    int good() { return new Bean("ok").work(); }
                    int guarded(String value) { if (value == null) return 0; return new Bean(value).work(); }
                    void optional() { new OptionalBean(null); }
                }
                """);
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().endsWith("#bad()")));
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && !f.methodId().endsWith("#bad()")));
        var report = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph());
        var completeness = report.path("analysisCompleteness");
        assertFalse(completeness.path("runtimeObserved").asBoolean(true));
        assertTrue(completeness.path("frameworkAssumptions").size() > 0);
        assertEquals(completeness.path("declaredBodies").asInt(), completeness.path("analyzedBodies").asInt());
        assertTrue(result.getFindings().stream().filter(f -> f.ruleId().equals("NG004")).allMatch(f -> f.location().startLine() > 0));
    }

    @Test void unresolvedCallsAndSourceProblemsCannotLookLikeCompleteAnalysis() throws Exception {
        Files.writeString(root.resolve("Broken.java"), "class Broken { void run( {");
        var result = run("class Example { Missing service; void run() { service.process(); } }");
        var report = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph());
        var completeness = report.path("analysisCompleteness");
        assertEquals("INCOMPLETE", completeness.path("status").asText());
        assertEquals(2, completeness.path("sourceFilesDiscovered").asInt());
        assertTrue(completeness.path("sourceFilesWithProblems").asInt() > 0);
        assertTrue(completeness.path("unresolvedCallSites").size() > 0);
        String html = HtmlDashboardRenderer.render(result, "test", "", "test");
        assertTrue(html.contains("Analysis completeness"));
        assertTrue(html.contains("Runtime observed: No"));
    }

    @Test void constructorBodiesAndDelegationAreAnalyzed() throws Exception {
        var result = run("""
                @interface NonNull {}
                class Base { Base(@NonNull String value) { value.length(); } }
                class Child extends Base { Child() { super(null); } }
                class Other {
                    Other() { this(null); }
                    Other(@NonNull String value) { value.length(); }
                }
                class Unsafe { Unsafe(String value) { value.length(); } }
                """);
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().equals("Child#<init>()")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().equals("Other#<init>()")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.methodId().equals("Unsafe#<init>(String)")));
    }

    @Test void constructorOverloadsDoNotShareRequiredParameters() throws Exception {
        var result = run("""
                @interface NonNull {}
                @interface Nullable {}
                class Item {
                    Item(@NonNull String value) {}
                    Item(@Nullable Integer value) {}
                }
                class Client {
                    void safe() { new Item("ok"); new Item((Integer) null); }
                    void bad() { String value = null; new Item(value); }
                }
                """);
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().endsWith("#safe()")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().endsWith("#bad()")));
    }

    @Test void unsupportedRecordBodiesAreNotSilentlyCountedAsAnalyzed() throws Exception {
        var result = run("record Example(String name) { int size() { return name.length(); } }");
        var completeness = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph()).path("analysisCompleteness");
        assertEquals("INCOMPLETE", completeness.path("status").asText());
        assertEquals(1, completeness.path("unmodeledExecutables").asInt());
        assertEquals(0, completeness.path("analyzedBodies").asInt());
    }

    @Test void emptyInputAndMissingAccountingAreNotComplete() {
        var empty = com.nullguard.core.model.ProjectModel.builder().projectName("empty").build();
        assertEquals("NO_SOURCE", AnalysisCompleteness.describe(empty).get("status"));
        var method = com.nullguard.core.model.MethodModel.builder().methodName("test").signature("test()")
                .declaredBody(true).build();
        var project = com.nullguard.core.model.ProjectModel.builder().projectName("manual")
                .addModule(com.nullguard.core.model.ModuleModel.builder().moduleName("root")
                        .addPackage(com.nullguard.core.model.PackageModel.builder().packageName("demo")
                                .addClass(com.nullguard.core.model.ClassModel.builder().className("Example").addMethod(method).build()).build()).build()).build();
        var completeness = AnalysisCompleteness.describe(project);
        assertEquals("UNAVAILABLE", completeness.get("status"));
        assertEquals(1, ((java.util.List<?>) completeness.get("cfgFailures")).size());
    }

    @Test void knownSourceWithNoCountedGapsIsNotLabeledRuntimeComplete() throws Exception {
        var result = run("class Example { int one() { return 1; } }");
        var completeness = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph()).path("analysisCompleteness");
        assertEquals("SOURCE_ANALYZED", completeness.path("status").asText());
        assertFalse(completeness.path("runtimeObserved").asBoolean(true));
        assertEquals(2, completeness.path("analyzedBodies").asInt()); // method + implicit constructor
    }

    @Test void dependencyAndExcludedAccessorCallsAreSeparateFromMissingSourceBodies() throws Exception {
        var result = run("""
                interface Work { void process(); }
                class Example {
                    Work worker;
                    void run() { worker.process(); String.valueOf(1); getName(); }
                    String getName() { return "ok"; }
                }
                """);
        var completeness = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph()).path("analysisCompleteness");
        assertEquals("LIMITED", completeness.path("status").asText());
        assertTrue(completeness.path("resolvedDependencyCallSites").asInt() > 0);
        assertTrue(completeness.path("excludedAccessorCallSites").asInt() > 0);
        assertTrue(completeness.path("sourceDispatchWithoutBody").size() > 0);
    }

    @Test void missingAnalysisArtifactIsAnIncompleteBody() throws Exception {
        Files.writeString(root.resolve("Example.java"), "class Example { int one() { return 1; } }");
        var project = new com.nullguard.core.parser.JavaParserAstParser().parse(root);
        var completeness = AnalysisCompleteness.describe(project);
        assertEquals("INCOMPLETE", completeness.get("status"));
        assertEquals(2, ((java.util.List<?>) completeness.get("analysisFailures")).size());
        assertEquals(0, completeness.get("analyzedBodies"));
    }
}
