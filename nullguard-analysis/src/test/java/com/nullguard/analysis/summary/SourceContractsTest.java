package com.nullguard.analysis.summary;

import com.nullguard.analysis.config.AnalysisConfig;
import com.nullguard.analysis.engine.NullAnalysisModel;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.parser.JavaParserAstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class SourceContractsTest {
    @TempDir Path root;

    private Map<String, MethodModel> analyze(String source) throws Exception {
        Files.writeString(root.resolve("Example.java"), source);
        var project = new JavaParserAstParser().parse(root);
        new MethodSummaryEngine(new AnalysisConfig(10, 0.85, 1.0, 60, 1)).run(project);
        Map<String, MethodModel> methods = new TreeMap<>();
        project.getModules().values().forEach(m -> m.getPackages().values().forEach(p ->
                p.getClasses().values().forEach(c -> c.getMethods().values().forEach(method -> methods.put(method.getMethodName(), method)))));
        return methods;
    }
    private NullState returned(MethodModel method) {
        return ((MethodSummary) method.getMethodSummary().orElseThrow()).getReturnNullability();
    }
    private int warnings(MethodModel method) {
        return ((NullAnalysisModel)method.getNullAnalysisModel().orElseThrow()).getUnguardedDereferences();
    }

    @Test void expressionBranchesAndCatchParametersAreScoped() throws Exception {
        var m = analyze("""
                class Example {
                    int ternary(String value) { return value != null ? value.length() : 0; }
                    int reverse(String value) { return value == null ? 0 : value.length(); }
                    int unsafe(String value) { return value == null ? value.length() : 0; }
                    int after(String value) { int n = value != null ? value.length() : 0; return value.length(); }
                    int pattern(Object value) { return value instanceof String text ? text.length() : 0; }
                    int negative(Object value) { if (!(value instanceof String text)) return 0; return text.length(); }
                    int conjunction(String a, String b) { if (a != null && b != null) return a.length() + b.length(); return 0; }
                    int disjunction(String a, String b) { if (a != null && b != null) return 0; return a.length(); }
                    int caught() { try { throw new RuntimeException(); } catch (RuntimeException ex) { return ex.hashCode(); } }
                }
                """);
        for (String name : java.util.List.of("ternary", "reverse", "pattern", "negative", "conjunction", "caught"))
            assertEquals(0, warnings(m.get(name)), name);
        for (String name : java.util.List.of("unsafe", "after", "disjunction")) assertTrue(warnings(m.get(name)) > 0, name);
    }

    @Test void springInjectionAndLombokAreExplicitAssumptions() throws Exception {
        var m = analyze("""
                import org.springframework.stereotype.Service;
                import lombok.extern.slf4j.Slf4j;
                @Service @Slf4j class Example {
                    final String service;
                    Example(String service) { this.service = service; }
                    int injected() { log.info("ok"); return service.length(); }
                }
                class Plain {
                    final String service;
                    Plain(String service) { this.service = service; }
                    int ordinary() { return service.length(); }
                }
                @Service class FieldBean {
                    @org.springframework.beans.factory.annotation.Autowired String required;
                    @org.springframework.beans.factory.annotation.Autowired(required=false) String optional;
                    @org.springframework.beans.factory.annotation.Autowired String resettable;
                    int requiredField() { return required.length(); }
                    int optionalField() { return optional.length(); }
                    int resetField() { resettable = null; return resettable.length(); }
                }
                @Service class NullableBean {
                    final String service;
                    NullableBean(@org.springframework.lang.Nullable String service) { this.service = service; }
                    int optional() { return service.length(); }
                }
                """);
        assertEquals(0, warnings(m.get("injected")));
        assertTrue(warnings(m.get("ordinary")) > 0);
        assertTrue(warnings(m.get("optional")) > 0);
        assertEquals(0, warnings(m.get("requiredField")));
        assertTrue(warnings(m.get("optionalField")) > 0);
        assertTrue(warnings(m.get("resetField")) > 0);
    }

    @Test void optionalAndStringContractsKeepNullableValuesNullable() throws Exception {
        var m = analyze("""
                class Example {
                    int optional(java.util.Optional<String> value) {
                        if (value == null) return 0;
                        return value.orElseThrow().trim().length();
                    }
                    int nullable(java.util.Optional<String> value) {
                        if (value == null) return 0;
                        return value.orElse(null).length();
                    }
                    int unrelated(Fake value) { if (value == null) return 0; return value.orElseThrow().length(); }
                }
                class Fake { String orElseThrow() { return null; } }
                """);
        assertEquals(0, warnings(m.get("optional")));
        assertTrue(warnings(m.get("nullable")) > 0);
        assertTrue(warnings(m.get("unrelated")) > 0);
    }

    @Test void frameworkParametersRespectOptionalBodies() throws Exception {
        var m = analyze("""
                import org.springframework.web.bind.annotation.*;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.ProceedingJoinPoint;
                class Example {
                    @PostMapping("/x") int body(@RequestBody String value) { return value.length(); }
                    @PostMapping("/y") int optionalBody(@RequestBody(required=false) String value) { return value.length(); }
                    @Around("@annotation(annotation)") int advice(ProceedingJoinPoint point, Deprecated annotation) { return annotation.hashCode() + point.hashCode(); }
                }
                """);
        assertEquals(0, warnings(m.get("body")));
        assertEquals(0, warnings(m.get("advice")));
        assertTrue(warnings(m.get("optionalBody")) > 0);
    }

    @Test void skippedChecksDoNotRefineFollowingStatements() throws Exception {
        var m = analyze("""
                class Example {
                    int skipped(String value, boolean flag) {
                        Object ignored = flag ? java.util.Objects.requireNonNull(value) : null;
                        return value.length();
                    }
                    int checked(String value) { java.util.Objects.requireNonNull(value); return value.length(); }
                }
                """);
        assertTrue(warnings(m.get("skipped")) > 0);
        assertEquals(0, warnings(m.get("checked")));
    }

    @Test void stableFieldGetterIsNonNullButMutableAndNullFieldsAreNot() throws Exception {
        var m = analyze("""
                class Example {
                    private final java.util.List<String> pets = new java.util.ArrayList<>();
                    private java.util.List<String> mutable = new java.util.ArrayList<>();
                    private final String absent = null;
                    java.util.List<String> getPets() { return this.pets; }
                    java.util.List<String> getMutable() { return mutable; }
                    String getAbsent() { return absent; }
                    int use() { return getPets().size(); }
                    int shadow(String pets) { return pets.length(); }
                }
                """);
        assertEquals(NullState.NON_NULL, returned(m.get("getPets")));
        assertNotEquals(NullState.NON_NULL, returned(m.get("getMutable")));
        assertNotEquals(NullState.NON_NULL, returned(m.get("getAbsent")));
        assertEquals(0, warnings(m.get("use")));
        assertTrue(warnings(m.get("shadow")) > 0);
    }

    @Test void jspecifyDefaultsRespectNullableAndUnmarkedScopes() throws Exception {
        Files.writeString(root.resolve("package-info.java"), "@org.jspecify.annotations.NullMarked package demo;");
        var m = analyze("""
                package demo;
                import org.jspecify.annotations.*;
                class Example {
                    int safe(String name) { return name.length(); }
                    int nullable(@Nullable String name) { return name.length(); }
                    @NullUnmarked int unknown(String name) { return name.length(); }
                    @Nullable String maybe() { return null; }
                    java.util.List<@Nullable String> container(java.util.List<@Nullable String> values) { return values; }
                    int array(String @Nullable [] values) { return values.length; }
                }
                """);
        assertTrue(m.get("safe").getParameters().get(0).nonNull());
        assertEquals(0, warnings(m.get("safe")));
        assertFalse(m.get("nullable").getParameters().get(0).nonNull());
        assertTrue(warnings(m.get("nullable")) > 0);
        assertFalse(m.get("unknown").getParameters().get(0).nonNull());
        assertFalse(m.get("maybe").isNonNullReturn());
        assertTrue(m.get("container").getParameters().get(0).nonNull());
        assertFalse(m.get("array").getParameters().get(0).nonNull());
    }

    @Test void springDefaultsArePackageLocalAndNullableWins() throws Exception {
        Files.writeString(root.resolve("package-info.java"), "@org.springframework.lang.NonNullApi package demo;");
        var m = analyze("""
                package demo;
                import org.springframework.lang.Nullable;
                class Example {
                    int safe(String name) { return name.length(); }
                    int nullable(@Nullable String name) { return name.length(); }
                }
                """);
        assertEquals(0, warnings(m.get("safe")));
        assertTrue(warnings(m.get("nullable")) > 0);
    }

    @Test void frameworkContractsRequireTheResolvedFrameworkOwner() {
        var framework = new com.nullguard.core.model.ResolvedCallTarget("org.springframework.aot.hint.RuntimeHints", "resources()", "resources", 0);
        var unrelated = new com.nullguard.core.model.ResolvedCallTarget("example.RuntimeHints", "resources()", "resources", 0);
        var m = MethodModel.builder().methodName("test").signature("test()")
                .semanticCallSites(java.util.List.of(
                    new com.nullguard.core.model.SemanticCallSite("hints.resources", 0, framework),
                    new com.nullguard.core.model.SemanticCallSite("other.resources", 0, unrelated))).build();
        var returns = MethodSummaryEngine.callReturns(m, Map.of());
        assertEquals(NullState.NON_NULL, returns.get("hints.resources/0"));
        assertEquals(NullState.UNKNOWN, returns.get("other.resources/0"));
    }

    @Test void defaultsDoNotEscapePackageOrAssumeGenericValuesNonNull() throws Exception {
        Files.writeString(root.resolve("package-info.java"), "@org.jspecify.annotations.NullMarked package marked;");
        var m = analyze("""
                package other;
                class Example {
                    int unknown(String name) { return name.length(); }
                }
                """);
        assertFalse(m.get("unknown").getParameters().get(0).nonNull());
        Files.writeString(root.resolve("package-info.java"), "@org.jspecify.annotations.NullMarked package other;");
        m = analyze("""
                package other;
                class Example<T> {
                    T identity(T value) { return value; }
                }
                """);
        assertFalse(m.get("identity").getParameters().get(0).nonNull());
    }

    @Test void survivingReceiversRefineDeclarationsAndComparisonsButNotReassignments() throws Exception {
        var m = analyze("""
                class Example {
                    int declared(String value) { int n = value.length(); return n + value.hashCode(); }
                    int compared(String value) { if (value.length() == 0) return 0; return value.hashCode(); }
                    int guardedRight(String value, boolean flag) { boolean b = flag && value.isEmpty(); return value.hashCode(); }
                    int reassigned(Node node) { node = node.next(); return node.hashCode(); }
                }
                class Node { Node next() { return null; } }
                """);
        // Only the first dereference is unguarded; surviving it proves the receiver non-null.
        assertEquals(1, warnings(m.get("declared")), "declared");
        assertEquals(1, warnings(m.get("compared")), "compared");
        // `value.isEmpty()` is skipped when flag is false, so it proves nothing afterwards.
        assertEquals(2, warnings(m.get("guardedRight")), "guardedRight");
        // The receiver is overwritten by a nullable result; the old fact must not survive.
        assertEquals(2, warnings(m.get("reassigned")), "reassigned");
    }
}
