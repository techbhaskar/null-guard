package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PrimitiveArgumentContractTest {
    @TempDir Path root;

    @Test void provenPrimitiveAndClassLiteralArgumentsAreSafeButNullableBoxesStillWarn() throws Exception {
        Files.writeString(root.resolve("Example.java"), """
                @interface NonNull { }
                class Required {
                    Required(int count, @NonNull Class<?> type) { }
                }
                class Delegating {
                    static int count;
                    Delegating() { this(count, String.class); }
                    Delegating(int count, @NonNull Class<?> type) { }
                }
                class BadDelegating extends Required {
                    BadDelegating(Integer value) { super(value, String.class); }
                }
                class Example {
                    int count;
                    Integer boxed;
                    static void number(int count) { }
                    static void character(char value) { }
                    static void type(@NonNull Class<?> type) { }
                    void good(char[] chars) {
                        number(count);
                        number(this.count);
                        number("ok".length());
                        character(chars[0]);
                        type(String.class);
                        type(int.class);
                        type(String[].class);
                        new Required(count, void.class);
                    }
                    void boxedBad(Integer value) { number(value); }
                    void fieldBad() { number(boxed); }
                    void castBad(Integer value) { number((int) value); }
                    void conditionalBad(Integer value, boolean flag) { number(flag ? value : 1); }
                    void assignmentBad(Integer value) { int copy; number(copy = value); }
                    void explicitUnbox(Integer value) { number(value.intValue()); }
                    void classBad(Class<?> value) { type(value); }
                    void constructorBad(Integer value) { new Required(value, String.class); }
                    void primitiveBoxed(@NonNull Integer ignored) { }
                    void goodBoxing() { primitiveBoxed(count); }
                }
                """);
        var result = new EngineBootstrap(NullGuardConfig.defaults().outputDirectory(root.resolve("out")).build()).run(root);
        var warnings = result.getFindings().stream().filter(f -> f.ruleId().equals("NG004")).toList();
        for (String method : new String[]{"good(char[])", "goodBoxing()"})
            assertFalse(warnings.stream().anyMatch(f -> f.methodId().equals("Example#" + method)), warnings.toString());
        for (String method : new String[]{"boxedBad(Integer)", "fieldBad()", "castBad(Integer)", "conditionalBad(Integer, boolean)",
                "assignmentBad(Integer)", "classBad(Class)", "constructorBad(Integer)"})
            assertTrue(warnings.stream().anyMatch(f -> f.methodId().equals("Example#" + method)), method + ": " + warnings);
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001")
                && f.methodId().equals("Example#explicitUnbox(Integer)")));
        assertFalse(warnings.stream().anyMatch(f -> f.methodId().equals("Delegating#<init>()")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(f -> f.methodId().equals("BadDelegating#<init>(Integer)")), warnings.toString());
    }
}
