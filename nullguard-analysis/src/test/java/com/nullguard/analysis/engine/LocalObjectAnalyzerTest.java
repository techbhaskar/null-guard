package com.nullguard.analysis.engine;

import com.nullguard.core.model.*;
import com.nullguard.core.parser.JavaParserAstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source-level lifecycle regressions, exercised directly through the analysis module. */
class LocalObjectAnalyzerTest {
    @TempDir Path root;

    private Map<String, MethodModel> analyze(String source) throws Exception {
        Path file = root.resolve("Example.java");
        Files.writeString(file, source);
        ProjectModel project = new JavaParserAstParser().parse(root);
        new LocalObjectAnalyzer().analyze(project);
        assertEquals(source, Files.readString(file), "Analysis must not modify application source");
        Map<String, MethodModel> result = new LinkedHashMap<>();
        project.getModules().values().forEach(m -> m.getPackages().values().forEach(p -> p.getClasses().values().forEach(c ->
                c.getMethods().values().forEach(method -> result.put(c.getClassName()+"#"+method.getMethodName(), method)))));
        return result;
    }
    private void issue(Map<String, MethodModel> methods, String name, boolean expected) {
        MethodModel method = Objects.requireNonNull(methods.get(name), name);
        assertEquals(expected, method.getLifecycleIssues().stream().anyMatch(f -> f.ruleId().equals("NG006")), name);
        assertTrue(method.getLifecycleIssues().stream().allMatch(f -> f.line() > 0));
    }
    private void limited(Map<String, MethodModel> methods, String name, String reason) {
        assertTrue(methods.get(name).getLifecycleLimitations().stream().anyMatch(s -> s.contains(reason)),
                () -> name+": "+methods.get(name).getLifecycleLimitations());
    }

    @Test void aliasesShareMutationsButIndependentAllocationsDoNot() throws Exception {
        var methods = analyze("""
            class Box {
                String value;
                Box(String input) { value = input; }
                void clear() { this.value = null; }
                int size() { return value.length(); }
            }
            class Client {
                int alias() { Box b = new Box("ok"); Box a = b; a.clear(); return b.size(); }
                int independent() { Box a = new Box("ok"); Box b = new Box(null); b.clear(); return a.size(); }
                int absent() { return new Box(null).size(); }
                int cast() { Box b = new Box("ok"); return ((Box) (b)).size(); }
            }
            """);
        issue(methods,"Client#alias",true);
        issue(methods,"Client#independent",false);
        issue(methods,"Client#absent",true);
        issue(methods,"Client#cast",false);
    }

    @Test void branchesMergeMutationsAndKeepUnreachableNullBranchOut() throws Exception {
        var methods = analyze("""
            class Box {
                String value;
                Box(String input) { this.value = input; }
                void clear() { value = null; }
                int size() { return value.length(); }
            }
            class Client {
                int maybe(boolean flag) { Box b = new Box("ok"); if (flag) b.clear(); else { ; } return b.size(); }
                int guarded() { Box b = new Box("ok"); if (b == null) { b.clear(); } return b.size(); }
                int branchReturn(boolean flag) { Box b = new Box("ok"); if (flag) return b.size(); b.clear(); return b.size(); }
            }
            """);
        issue(methods,"Client#maybe",true);
        issue(methods,"Client#guarded",false);
        issue(methods,"Client#branchReturn",true);
    }

    @Test void constructorDelegationAndStaticInitializationPreserveEffects() throws Exception {
        var methods = analyze("""
            class Box {
                static String shared = "ready";
                Box sibling;
                String value;
                Box() { this("ready"); }
                Box(String input) { this.value = input; }
                int size() { return value.length() + shared.length(); }
                static int constant() { return 1; }
            }
            class Client {
                int initialized() { return new Box().size(); }
                int staticQualifier() { Box b = new Box(null); return b.sibling.constant(); }
            }
            """);
        issue(methods,"Client#initialized",false);
        // A static call through a nullable qualifier must not create a lifecycle dereference.
        issue(methods,"Client#staticQualifier",false);
    }

    @Test void escapingObjectsInvalidatePreviouslySafeFields() throws Exception {
        var methods = analyze("""
            class Box {
                String value;
                Box() { value = "ready"; }
                int size() { return value.length(); }
            }
            class Client {
                native void escape(Box b);
                int escaped() { Box b = new Box(); escape(b); return b.size(); }
                int dependency() { Box b = new Box(); b.toString(); return b.size(); }
            }
            """);
        issue(methods,"Client#escaped",true);
        limited(methods,"Client#escaped","escaping object");
        issue(methods,"Client#dependency",true);
    }

    @Test void unsupportedControlFlowAndExpressionsAreReportedAsLimitations() throws Exception {
        var methods = analyze("""
            class Client {
                void loop() { while (true) { break; } }
                void conditional(boolean flag) { String s = flag ? "yes" : null; }
                boolean shortCircuit(boolean flag) { return flag && true; }
                boolean otherShortCircuit(boolean flag) { return flag || false; }
                int binary() { return 1 + 2; }
                void literal() { int[] values = new int[2]; String unused; return; }
            }
            """);
        limited(methods,"Client#loop","loops, try/switch");
        limited(methods,"Client#conditional","unsupported expression");
        limited(methods,"Client#shortCircuit","short-circuit");
        limited(methods,"Client#otherShortCircuit","short-circuit");
        assertTrue(methods.get("Client#binary").getLifecycleLimitations().isEmpty());
        assertTrue(methods.get("Client#literal").getLifecycleIssues().isEmpty());
    }

    @Test void recursionAndExceptionalExitsDoNotPretendToProveSafeState() throws Exception {
        var methods = analyze("""
            class Box {
                String value;
                Box() { value = "ready"; }
                void recurse() { recurse(); }
                void fail() { throw new IllegalStateException(); }
                int size() { return value.length(); }
            }
            class Client {
                int recursive() { Box b = new Box(); b.recurse(); return b.size(); }
                int exceptional() { Box b = new Box(); b.fail(); return b.size(); }
            }
            """);
        limited(methods,"Client#recursive","depth/work budget");
        limited(methods,"Client#exceptional","no normal constructor/method exit");
    }
}
