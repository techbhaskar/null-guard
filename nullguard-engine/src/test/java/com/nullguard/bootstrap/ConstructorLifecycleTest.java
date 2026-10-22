package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConstructorLifecycleTest {
    @TempDir Path root;
    private FinalAnalysisResult run(String source) throws Exception {
        Files.writeString(root.resolve("Example.java"), source);
        var result = new EngineBootstrap(NullGuardConfig.defaults().outputDirectory(root.resolve("out")).build()).run(root);
        assertEquals(source, Files.readString(root.resolve("Example.java")), "AST lowering must not edit service source files");
        assertTrue(result.getFindings().stream().filter(f -> f.ruleId().equals("NG006"))
                .allMatch(f -> f.location() != null && f.location().startLine() > 0 && f.location().startLine() <= source.lines().count()),
                "Lifecycle locations must refer to original source lines, not pretty-printed synthetic bodies");
        return result;
    }
    private boolean has(FinalAnalysisResult result, String rule, String method) {
        return result.getFindings().stream().anyMatch(f -> f.ruleId().equals(rule) && f.methodId().endsWith("#" + method));
    }

    @Test void lombokConstructorsResolveAndEnforceOnlyDeclaredRequiredFields() throws Exception {
        var result = run("""
                import lombok.RequiredArgsConstructor;
                import lombok.AllArgsConstructor;
                import lombok.NonNull;
                @RequiredArgsConstructor class Required { @NonNull final String value; int size() { return value.length(); } }
                @AllArgsConstructor class Optional { String value; }
                class Client {
                    void bad() { new Required(null); }
                    void good() { new Required("ok"); }
                    void optional() { new Optional(null); }
                }
                """);
        assertTrue(has(result, "NG004", "bad()"));
        assertFalse(has(result, "NG004", "good()"));
        assertFalse(has(result, "NG004", "optional()"));
        var completeness = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph()).path("analysisCompleteness");
        assertTrue(completeness.path("generatedExecutables").size() > 0);
    }

    @Test void implicitConstructorsAndInitializersPreserveOrderAndSourceFindings() throws Exception {
        var result = run("""
                class Box {
                    String value = "ready";
                    { value = null; }
                    int size() { return value.length(); }
                }
                class Broken { static String absent = null; static { absent.length(); } }
                class Client { int bad() { return new Box().size(); } }
                """);
        assertTrue(has(result, "NG006", "bad()"));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.methodId().contains("$nullguard$clinit")));
    }

    @Test void localAliasesMutationsAndIndependentAllocationsStaySeparate() throws Exception {
        var result = run("""
                class Box {
                    String value;
                    Box(String value) { this.value = value; }
                    void reset() { value = null; }
                    int size() { return value.length(); }
                }
                class Client {
                    int bad() { Box box = new Box("ok"); Box alias = box; alias.reset(); return box.size(); }
                    int good() { Box a = new Box("ok"); Box b = new Box(null); b.reset(); return a.size(); }
                    int defaultBad() { return new Box(null).size(); }
                }
                """);
        assertTrue(has(result, "NG006", "bad()"));
        assertTrue(has(result, "NG006", "defaultBad()"));
        assertFalse(has(result, "NG006", "good()"));
    }

    @Test void sourceValidatorEffectsRequireProvenUnconditionalResolvedChecks() throws Exception {
        var result = run("""
                class Checks {
                    static void present(String value) { if (value == null) throw new IllegalArgumentException(); }
                    static void pretend(String value) { }
                }
                class Client {
                    int good(String value) { Checks.present(value); return value.length(); }
                    int bad(String value) { Checks.pretend(value); return value.length(); }
                    int skipped(String value, boolean flag) { if (flag) Checks.present(value); return value.length(); }
                }
                """);
        assertFalse(has(result, "NG001", "good(String)"));
        assertTrue(has(result, "NG001", "bad(String)"));
        assertTrue(has(result, "NG001", "skipped(String, boolean)"));
    }

    @Test void initializerBlocksRunBeforeConstructorBodyButNotTwiceThroughDelegation() throws Exception {
        var result = run("""
                class Box {
                    String value = null;
                    { value = "ready"; }
                    Box() { this("override"); }
                    Box(String value) { this.value = value; }
                    int size() { return value.length(); }
                }
                class Client { int good() { return new Box().size(); } }
                """);
        assertFalse(has(result, "NG006", "good()"));
    }

    @Test void manuallyCreatedFieldInjectedBeanIsNotTreatedAsFrameworkInitialized() throws Exception {
        var result = run("""
                import org.springframework.stereotype.Service;
                import org.springframework.beans.factory.annotation.Autowired;
                @Service class Bean {
                    @Autowired String dependency;
                    int use() { return dependency.length(); }
                }
                class Client { int bad() { return new Bean().use(); } }
                """);
        assertTrue(has(result, "NG006", "bad()"));
    }

    @Test void noArgsForceAndUnknownEscapesRemainConservative() throws Exception {
        var result = run("""
                import lombok.NoArgsConstructor;
                @NoArgsConstructor(force=true) class Box {
                    final String value;
                    int size() { return value.length(); }
                }
                class Client {
                    int bad() { return new Box().size(); }
                    int escaped() { Box box = new Box(); Missing.touch(box); return box.size(); }
                }
                """);
        assertTrue(has(result, "NG006", "bad()"));
        assertTrue(has(result, "NG006", "escaped()"));
        var completeness = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph()).path("analysisCompleteness");
        assertTrue(completeness.path("localLifecycleLimitations").size() > 0);
    }

    @Test void caughtValidationDoesNotPromiseNonnullOnReturn() throws Exception {
        var result = run("""
                class Checks {
                    static void swallowed(String value) {
                        try { java.util.Objects.requireNonNull(value); } catch (Exception ignored) { }
                    }
                }
                class Client { int bad(String value) { Checks.swallowed(value); return value.length(); } }
                """);
        assertTrue(has(result, "NG001", "bad(String)"));
    }

    @Test void unrelatedObjectsClassCannotMasqueradeAsJdkValidation() throws Exception {
        var result = run("""
                class Objects { static String requireNonNull(String value) { return value; } }
                class Client {
                    int bad(String value) { Objects.requireNonNull(value); return value.length(); }
                    int good(String value) { java.util.Objects.requireNonNull(value); return value.length(); }
                }
                """);
        assertTrue(has(result, "NG001", "bad(String)"));
        assertFalse(has(result, "NG001", "good(String)"));
    }

    @Test void staticInitializerNameCannotOverwriteAnApplicationMethod() throws Exception {
        var result = run("""
                class Box {
                    static String value = "ready";
                    static void $nullguard$clinit() { value = null; }
                    int size() { return value.length(); }
                }
                class Client { int good() { return new Box().size(); } }
                """);
        assertFalse(has(result, "NG006", "good()"));
        var completeness = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph()).path("analysisCompleteness");
        assertTrue(completeness.path("generatedExecutables").toString().contains("$nullguard$clinit$()"));
    }

    @Test void constructorValidationContractsPropagateThroughResolvedHelpers() throws Exception {
        var result = run("""
                class Checks { static void present(String value) { if (value == null) throw new IllegalArgumentException(); } }
                class Box {
                    String value;
                    Box(String value) { Checks.present(value); this.value = value; }
                }
                class Client {
                    void bad() { new Box(null); }
                    void good() { new Box("ready"); }
                }
                """);
        assertTrue(has(result, "NG004", "bad()"));
        assertFalse(has(result, "NG004", "good()"));
    }

    @Test void annotationImportsDoNotLeakConstructorOrFrameworkAssumptionsAcrossClasses() throws Exception {
        var result = run("""
                import lombok.RequiredArgsConstructor;
                import lombok.NonNull;
                import org.springframework.stereotype.Service;
                import org.springframework.beans.factory.annotation.Autowired;
                @RequiredArgsConstructor class Required { @NonNull final String value; }
                @Service class Bean { @Autowired String value; int size() { return value.length(); } }
                class Client { int bad() { return new Bean().size(); } }
                """);
        assertTrue(has(result, "NG006", "bad()"));
        var completeness = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph()).path("analysisCompleteness");
        assertTrue(completeness.path("generatedExecutables").toString().contains("Bean#<init>()"));
        assertFalse(completeness.path("generatedExecutables").toString().contains("Bean#<init>(String)"));
        assertFalse(completeness.path("frameworkAssumptions").toString().contains("Required#"));
    }
}
