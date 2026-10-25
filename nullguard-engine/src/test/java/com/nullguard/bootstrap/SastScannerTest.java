package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SastScannerTest {
    @TempDir Path root;

    @Test
    void reportsExplicitlyWhenNoCompiledClassesAreConfigured() {
        Map<String, Object> result = SastScanner.scan(NullGuardConfig.defaults().build());

        assertEquals("NOT_RUN", result.get("status"));
        assertEquals(List.of(), result.get("findings"));
        assertTrue(((String) result.get("reason")).contains("No compiled class directories"));
    }

    @Test
    void reportsExplicitlyWhenConfiguredClassesAreUnavailable() {
        Map<String, Object> result = SastScanner.scan(NullGuardConfig.defaults()
                .sastClassDirectories(List.of(root.resolve("not-compiled")))
                .build());

        assertEquals("NOT_RUN", result.get("status"));
        assertTrue(((String) result.get("reason")).contains("class directory is unavailable"));
    }

    @Test
    void runsSpotBugsAndFindSecBugsAgainstConfiguredClasses() throws Exception {
        Path source = Files.createDirectories(root.resolve("src/example"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path javaFile = Files.writeString(source.resolve("UnsafeQuery.java"), """
                package example;
                import java.sql.Connection;
                import java.sql.SQLException;
                import java.sql.Statement;
                public class UnsafeQuery {
                    public void query(Connection connection, String userInput) throws SQLException {
                        Statement statement = connection.createStatement();
                        statement.executeQuery("SELECT * FROM users WHERE name = '" + userInput + "'");
                    }
                }
                """);
        int compileResult = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), javaFile.toString());
        assertEquals(0, compileResult);

        var result = new EngineBootstrap(NullGuardConfig.defaults()
                .sastClassDirectories(List.of(classes))
                .build()).run(root.resolve("src"));
        var report = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph());
        var summary = report.path("sastSummary");
        assertEquals("COMPLETED", summary.path("status").asText());
        assertEquals(1, summary.path("scannedClassCount").asInt());
        assertTrue(summary.path("findings").toString().contains("SQL_INJECTION"));
        String html = HtmlDashboardRenderer.render(result, "test", "", "fixture");
        assertTrue(html.contains("id=\"sast\""));
        assertTrue(html.contains("SQL_INJECTION"));
    }
}
