package com.nullguard.core.parser;

import com.nullguard.core.exception.CoreAnalysisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ParserDiagnosticsTest {
    @TempDir Path root;

    @Test void ignoresBrokenClasspathJarsAndRecordsMalformedSources() throws Exception {
        Path jar = Files.writeString(root.resolve("broken.jar"), "not a zip");
        Path classes = Files.createDirectories(root.resolve("classes"));
        Files.writeString(root.resolve("Good.java"), "class Good { int answer() { return 42; } }");
        Files.writeString(root.resolve("Broken.java"), "class Broken { void (");
        var parser = new JavaParserAstParser(Arrays.asList(root, root, null, root.resolve("missing")),
                List.of(jar, classes, root.resolve("absent.jar")));
        var project = parser.parse(root);
        var methods = project.getModules().values().stream().flatMap(m -> m.getPackages().values().stream())
                .flatMap(p -> p.getClasses().values().stream()).flatMap(c -> c.getMethods().values().stream()).toList();
        assertTrue(methods.stream().anyMatch(m -> m.getMethodName().equals("answer")));
        assertEquals(2, project.getParseDiagnostics().discoveredFiles());
        assertEquals(1, project.getParseDiagnostics().problemFiles());
        assertEquals(List.of("Broken.java"), project.getParseDiagnostics().problemPaths());
    }

    @Test void parsesJava21PatternSwitchAndLocatesUnsupportedDeclarations() throws Exception {
        Files.writeString(root.resolve("Example.java"), """
                class Example {
                    int length(Object value) {
                        return switch (value) {
                            case String text when !text.isEmpty() -> text.length();
                            case null, default -> 0;
                        };
                    }
                }
                record Data(String text) {
                    Data { }
                    int size() { return text.length(); }
                }
                """);
        var diagnostics = new JavaParserAstParser().parse(root).getParseDiagnostics();
        assertEquals(0, diagnostics.problemFiles());
        assertEquals(3, diagnostics.discoveredExecutables());
        assertEquals(List.of(11, 10), diagnostics.unsupportedExecutableLocations().stream()
                .map(com.nullguard.core.model.SourceLocation::startLine).toList());
        assertDoesNotThrow(() -> SourceExpressions.parseExpression(
                "switch (value) { case String text when !text.isEmpty() -> 1; default -> 0; }"));
    }

    @Test void missingRootRaisesAnalysisException() {
        var parser = new JavaParserAstParser(null, null);
        assertThrows(CoreAnalysisException.class, () -> parser.parse(root.resolve("missing")));
    }
}
