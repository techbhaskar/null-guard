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
}
