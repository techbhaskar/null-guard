package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ConstructorFieldInvariantTest {
    @TempDir Path root;

    @Test void finalFieldsRequireEveryNormalConstructorPathAndNoEarlyPublication() throws Exception {
        Files.writeString(root.resolve("Example.java"), """
                import java.util.ArrayList;
                import java.util.Objects;
                class Safe {
                    private final ArrayList<String> elements;
                    Safe() { elements = new ArrayList<>(); }
                    Safe(int capacity) { elements = new ArrayList<>(capacity); }
                    int size() { return elements.size(); }
                    int shadow(String elements) { return elements.length(); }
                }
                class Guarded {
                    private final String value;
                    Guarded(String value) { this.value = Objects.requireNonNull(value); }
                    int size() { return value.length(); }
                }
                class Delegating {
                    private final String value;
                    Delegating() { this("ready"); }
                    Delegating(String value) { if (value == null) throw new IllegalArgumentException(); this.value = value; }
                    int size() { return value.length(); }
                }
                class Nullable {
                    private final String value;
                    Nullable() { value = "ready"; }
                    Nullable(String value) { this.value = value; }
                    int size() { return value.length(); }
                }
                class Mutable {
                    private String value;
                    Mutable() { value = "ready"; }
                    void reset() { value = null; }
                    int size() { return value.length(); }
                }
                class Early {
                    private final String value;
                    Early() { size(); value = "ready"; }
                    private int size() { return value.length(); }
                }
                class Escaped {
                    static Escaped exposed;
                    private final String value;
                    Escaped() { exposed = this; value = "ready"; }
                    int size() { return value.length(); }
                }
                class Base { Base() { read(); } int read() { return 0; } }
                class Sub extends Base {
                    private final String value;
                    Sub() { value = "ready"; }
                    @Override int read() { return value.length(); }
                }
                class EmptyBase { void callback() { read(); } int read() { return 0; } }
                class SuperCall extends EmptyBase {
                    private final String value;
                    SuperCall() { super.callback(); value = "ready"; }
                    @Override int read() { return value.length(); }
                }
                class MixedBranches {
                    private final String value;
                    MixedBranches(boolean flag) { if (flag) value = "ready"; else value = null; }
                    int size() { return value.length(); }
                }
                class Swallowed {
                    private final String value;
                    Swallowed(String value) { try { Objects.requireNonNull(value); } catch (Exception ignored) { } this.value = value; }
                    int size() { return value.length(); }
                }
                class Restored implements java.io.Serializable {
                    private final String value;
                    Restored(String value) { this.value = Objects.requireNonNull(value); }
                    int size() { return value.length(); }
                }
                class Registry { static void publish(Published value) { value.size(); } }
                class Published {
                    private final String value;
                    Published() { Registry.publish(this); value = "ready"; }
                    int size() { return value.length(); }
                }
                class Deferred {
                    private final String value;
                    Deferred() { value = "ready"; }
                    Object callback() { return new Object() { int size(String value) { return value.length(); } }; }
                }
                """);
        var result = new EngineBootstrap(NullGuardConfig.defaults().outputDirectory(root.resolve("out")).build()).run(root);
        var warnings = result.getFindings().stream().filter(f -> f.ruleId().equals("NG001")).toList();
        for (String method : new String[]{"Safe#size()", "Guarded#size()", "Delegating#size()"})
            assertFalse(warnings.stream().anyMatch(f -> f.methodId().equals(method)), method + ": " + warnings);
        for (String method : new String[]{"Safe#shadow(String)", "Nullable#size()", "Mutable#size()", "Early#size()", "Escaped#size()", "Sub#read()",
                "SuperCall#read()", "MixedBranches#size()", "Swallowed#size()", "Restored#size()", "Published#size()", "Deferred#callback()"})
            assertTrue(warnings.stream().anyMatch(f -> f.methodId().equals(method)), method + ": " + warnings);
    }
}
