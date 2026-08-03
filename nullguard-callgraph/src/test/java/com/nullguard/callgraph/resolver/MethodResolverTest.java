package com.nullguard.callgraph.resolver;

import com.nullguard.callgraph.model.ExternalReason;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The old resolver matched with {@code signature.contains(name)}, so a call to {@code get()}
 * resolved to {@code getUserById(String)} and overloads were indistinguishable.
 */
class MethodResolverTest {

    private static MethodModel method(String name, String signature) {
        return MethodModel.builder().methodName(name).signature(signature).build();
    }

    private static ProjectModel project(String pkgName, String clsName, MethodModel... methods) {
        ClassModel.Builder cls = ClassModel.builder().className(clsName);
        for (MethodModel m : methods) cls.addMethod(m);
        return ProjectModel.builder().projectName("p")
                .addModule(ModuleModel.builder().moduleName("root")
                        .addPackage(PackageModel.builder().packageName(pkgName)
                                .addClass(cls.build()).build())
                        .build())
                .build();
    }

    @Test
    @DisplayName("substring matching no longer resolves get() to getUserById(String)")
    void noSubstringFalsePositive() {
        ProjectModel p = project("com.acme", "Repo", method("getUserById", "getUserById(String)"));

        assertTrue(new MethodResolver().resolveAll(p, "get").isEmpty(),
                "a call to get() must not resolve to getUserById");
    }

    @Test
    @DisplayName("a call named after a parameter type does not resolve")
    void parameterTypeNameDoesNotMatch() {
        ProjectModel p = project("com.acme", "Svc", method("handle", "handle(String)"));

        assertTrue(new MethodResolver().resolveAll(p, "String").isEmpty());
    }

    @Test
    @DisplayName("exact simple name still resolves")
    void exactNameResolves() {
        MethodModel m = method("findByEmail", "findByEmail(String)");
        ProjectModel p = project("com.acme", "Repo", m);

        assertEquals(List.of(MethodIds.of("com.acme", "Repo", "findByEmail(String)")),
                new MethodResolver().resolveAll(p, "repo.findByEmail"));
    }

    @Test
    @DisplayName("argument count discriminates overloads")
    void arityDiscriminatesOverloads() {
        ProjectModel p = project("com.acme", "Svc",
                method("foo", "foo(int)"),
                method("foo", "foo(String, String)"));

        List<String> oneArg = new MethodResolver().resolveAll(p, "foo", 1);
        assertEquals(1, oneArg.size());
        assertTrue(oneArg.get(0).endsWith("#foo(int)"));

        List<String> twoArgs = new MethodResolver().resolveAll(p, "foo", 2);
        assertEquals(1, twoArgs.size());
        assertTrue(twoArgs.get(0).endsWith("#foo(String, String)"));
    }

    @Test
    @DisplayName("an unknown argument count keeps every name match")
    void unknownArityKeepsAllCandidates() {
        ProjectModel p = project("com.acme", "Svc",
                method("foo", "foo(int)"),
                method("foo", "foo(String, String)"));

        assertEquals(2, new MethodResolver()
                .resolveAll(p, "foo", MethodResolver.MethodCallArity.UNKNOWN).size());
    }

    @Test
    @DisplayName("an arity that matches nothing falls back rather than dropping the edge")
    void arityFallsBackWhenNothingMatches() {
        ProjectModel p = project("com.acme", "Svc", method("foo", "foo(int)"));

        assertFalse(new MethodResolver().resolveAll(p, "foo", 7).isEmpty(),
                "textual arg counting is imprecise; losing a real edge is worse than a loose match");
    }

    @Test
    @DisplayName("generic parameters do not inflate the arity")
    void arityIgnoresCommasInsideGenerics() {
        assertEquals(1, MethodResolver.arityOf("save(Map<String, List<Integer>>)"));
        assertEquals(2, MethodResolver.arityOf("save(Map<String, Integer>, String)"));
        assertEquals(0, MethodResolver.arityOf("save()"));
    }

    @Test
    @DisplayName("external callees are classified rather than lumped together")
    void externalClassification() {
        MethodResolver r = new MethodResolver();

        // ExternalReason and ExternalMethodNode were previously never constructed at all.
        assertEquals(ExternalReason.JDK, r.classifyExternal("Objects.requireNonNull"));
        assertEquals(ExternalReason.JDK, r.classifyExternal("String.valueOf"));
        assertEquals(ExternalReason.THIRD_PARTY, r.classifyExternal("StringUtils.isBlank"));
        assertEquals(ExternalReason.UNRESOLVED, r.classifyExternal("someBean.doThing"));
        assertEquals(ExternalReason.UNRESOLVED, r.classifyExternal("bareCall"));
    }
}
