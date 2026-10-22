package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class StaticQualifierLifecycleTest {
    @TempDir Path root;

    @Test void staticCallsDoNotDereferenceTypeQualifiersButInstanceReceiversStillDo() throws Exception {
        Files.writeString(root.resolve("Example.java"), """
                import java.util.Objects;
                import java.util.Arrays;
                import java.util.Collections;
                class Safe {
                    void work() {
                        Objects.requireNonNull("ready");
                        Arrays.asList("ready");
                        Collections.emptyList();
                        java.util.regex.Pattern.compile("ready");
                    }
                }
                class Shadowed {
                    Shadowed Objects;
                    private void hit() { }
                    void work() { Objects.hit(); }
                }
                class Nested {
                    Nested absent;
                    Nested self() { return this; }
                    static void finish() { }
                    void work() { absent.self().finish(); }
                }
                class StaticThroughNull {
                    StaticThroughNull absent;
                    static void finish() { }
                    void work() { absent.finish(); }
                }
                class Client {
                    void good() { new Safe().work(); }
                    void bad() { new Shadowed().work(); }
                    void nestedBad() { new Nested().work(); }
                    void legalStatic() { new StaticThroughNull().work(); }
                }
                """);
        var result = new EngineBootstrap(NullGuardConfig.defaults().outputDirectory(root.resolve("out")).build()).run(root);
        var warnings = result.getFindings().stream().filter(f -> f.ruleId().equals("NG006")).toList();
        assertFalse(warnings.stream().anyMatch(f -> f.methodId().equals("Client#good()")), warnings.toString());
        assertFalse(warnings.stream().anyMatch(f -> f.methodId().equals("Client#legalStatic()")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(f -> f.methodId().equals("Client#bad()")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(f -> f.methodId().equals("Client#nestedBad()")), warnings.toString());
    }
}
