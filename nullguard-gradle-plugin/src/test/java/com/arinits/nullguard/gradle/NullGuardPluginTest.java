package com.arinits.nullguard.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NullGuardPluginTest {
    @TempDir Path project;

    @BeforeEach void stubAnalyzer() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'consumer'\n");
        Path source = project.resolve("stub/com/nullguard/cli/NullGuardCliApplication.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.nullguard.cli;
                import java.nio.file.*;
                import java.util.*;
                public class NullGuardCliApplication {
                    public static void main(String[] args) throws Exception {
                        Path root = Path.of(args[0]);
                        Path output = Path.of(Arrays.stream(args).filter(a -> a.startsWith("--output=")).findFirst().orElseThrow().substring(9));
                        Files.createDirectories(output);
                        List<String> lines = new ArrayList<>(Arrays.asList(args));
                        lines.add("SAST_CLASSES:" + System.getProperty("nullguard.sast.classes", ""));
                        try (var paths = Files.walk(root)) {
                            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> lines.add("SOURCE:" + root.relativize(p).toString().replace('\\\\', '/')));
                        }
                        Files.write(output.resolve("invocation.txt"), lines);
                        Files.writeString(output.resolve("nullguard-report-latest.json"), "{}");
                        if (Arrays.asList(args).contains("--fail-build")) System.exit(1);
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", project.resolve("stub").toString(), source.toString()));
        Path compiled = project.resolve("stub/com/nullguard/cli/NullGuardCliApplication.class");
        try (var jar = new JarOutputStream(Files.newOutputStream(project.resolve("analyzer.jar")))) {
            jar.putNextEntry(new JarEntry("com/nullguard/cli/NullGuardCliApplication.class"));
            Files.copy(compiled, jar); jar.closeEntry();
        }
    }
    private void source(String path) throws Exception {
        Path file = project.resolve(path); Files.createDirectories(file.getParent());
        Files.writeString(file, "package demo; class Sample {}\n");
    }
    private GradleRunner runner(String... args) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withGradleVersion(System.getProperty("test.gradle.version", "8.14.4")).withArguments(args);
    }
    private String groovy() {
        return """
                plugins { id 'java'; id 'com.arinits.nullguard' }
                tasks.named('nullguard') { classpath = files('analyzer.jar') }
                """;
    }
    @Test void groovySourcesClasspathExclusionsAndConfigurationCache() throws Exception {
        source("src/main/java/demo/Main.java"); source("extra/demo/Extra.java"); source("extra/demo/Excluded.java");
        Files.writeString(project.resolve("build.gradle"), groovy() + """
                dependencies { implementation files('analyzer.jar') }
                nullguard { excludes.set(['**/Excluded.java']); failThreshold.set('MEDIUM') }
                tasks.named('nullguard') { sourceFiles.from(fileTree('extra')) }
                """);
        var result = runner("nullguard", "--configuration-cache", "--stacktrace").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":nullguard").getOutcome());
        String invocation = Files.readString(project.resolve("build/reports/nullguard/invocation.txt"));
        assertTrue(invocation.contains("SOURCE:demo/Main.java"));
        assertTrue(invocation.contains("SOURCE:demo/Extra.java"));
        assertFalse(invocation.contains("SOURCE:demo/Excluded.java"));
        String classpath = invocation.lines().filter(line -> line.startsWith("--classpath=")).findFirst().orElseThrow().substring(12);
        assertTrue(java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .anyMatch(path -> Path.of(path).toAbsolutePath().normalize().toString().endsWith("analyzer.jar")), invocation);
        assertTrue(invocation.contains("SAST_CLASSES:"), invocation);
        assertTrue(invocation.contains("--fail-threshold=MODERATE"));
        assertFalse(invocation.contains("--fail-build"));
        assertTrue(runner("nullguard", "--configuration-cache", "--rerun-tasks").build().getOutput().contains("Reusing configuration cache"));
    }
    @Test void kotlinDslAndCustomReports() throws Exception {
        source("src/main/java/demo/Main.java");
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins { java; id("com.arinits.nullguard") }
                nullguard { reportDirectory.set(layout.buildDirectory.dir("custom-nullguard")) }
                tasks.named<com.arinits.nullguard.gradle.NullGuardTask>("nullguard") { classpath = files("analyzer.jar") }
                """);
        runner("nullguard").build();
        assertTrue(Files.exists(project.resolve("build/custom-nullguard/invocation.txt")));
    }

    @Test void forwardsOpenApiConfigurationFile() throws Exception {
        source("src/main/java/demo/Main.java");
        Files.writeString(project.resolve("api-docs.json"), "{}");
        Files.writeString(project.resolve("build.gradle"), groovy() + "\nnullguard { openApiConfig = layout.projectDirectory.file('api-docs.json') }\n");
        assertEquals(TaskOutcome.SUCCESS, runner("nullguard").build().task(":nullguard").getOutcome());
        String invocation = Files.readString(project.resolve("build/reports/nullguard/invocation.txt"));
        String configured = invocation.lines().filter(line -> line.startsWith("--openapi-config=")).findFirst().orElseThrow().substring(17);
        assertEquals(project.resolve("api-docs.json").toRealPath(), Path.of(configured).toRealPath());
    }
    @Test void forwardsExistingCompiledClassesForSastWithoutCompilingTheProject() throws Exception {
        source("src/main/java/demo/Main.java");
        Path classes = Files.createDirectories(project.resolve("build/classes/java/main"));
        Files.writeString(classes.resolve("Main.class"), "compiled fixture");
        Files.writeString(project.resolve("build.gradle"), groovy());

        assertEquals(TaskOutcome.SUCCESS, runner("nullguard").build().task(":nullguard").getOutcome());
        String invocation = Files.readString(project.resolve("build/reports/nullguard/invocation.txt"));
        assertTrue(invocation.contains("SAST_CLASSES:" + classes), invocation);
    }
    @Test void failuresPropagateWhenOptingIntoCheck() throws Exception {
        source("src/main/java/demo/Main.java");
        Files.writeString(project.resolve("build.gradle"), groovy() + "nullguard { failBuild.set(true); runOnCheck.set(true) }\n");
        var result = runner("check").buildAndFail();
        assertEquals(TaskOutcome.FAILED, result.task(":nullguard").getOutcome());
        assertTrue(Files.exists(project.resolve("build/reports/nullguard/invocation.txt")));
    }
    @Test void projectsWithoutSourcesSkipAndCheckIsOptIn() throws Exception {
        Files.writeString(project.resolve("build.gradle"), groovy());
        assertEquals(TaskOutcome.NO_SOURCE, runner("nullguard").build().task(":nullguard").getOutcome());
        assertNull(runner("check").build().task(":nullguard"));
    }
    @Test void duplicateRelativeSourcePathsFailClearly() throws Exception {
        source("src/main/java/demo/Main.java"); source("extra/demo/Main.java");
        Files.writeString(project.resolve("build.gradle"), groovy() + "tasks.named('nullguard') { sourceFiles.from(fileTree('extra')) }\n");
        var result = runner("nullguard").buildAndFail();
        assertEquals(TaskOutcome.FAILED, result.task(":nullguard").getOutcome());
        assertTrue(result.getOutput().replace('\\', '/').contains("demo/Main.java"), result.getOutput());
    }
    @Test void subprojectsHaveSeparateTasksAndReports() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='consumer'\ninclude 'service'\n");
        Path service = project.resolve("service"); Files.createDirectories(service);
        Files.copy(project.resolve("analyzer.jar"), service.resolve("analyzer.jar"));
        Files.writeString(service.resolve("build.gradle"), groovy());
        source("service/src/main/java/demo/Main.java");
        runner(":service:nullguard").build();
        assertTrue(Files.exists(service.resolve("build/reports/nullguard/invocation.txt")));
        assertFalse(Files.exists(project.resolve("build/reports/nullguard/invocation.txt")));
    }

    @Test void publishedCliAnalyzesJavaWithoutCompilingTheApplication() throws Exception {
        source("src/main/java/demo/Main.java");
        Files.writeString(project.resolve("src/main/java/demo/Main.java"), """
                package demo;
                class Main {
                    int broken() { String missing = null; return missing.length(); }
                }
                """);
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'java'; id 'com.arinits.nullguard' }
                repositories { mavenCentral() }
                nullguard { toolVersion.set("1.0.0") }
                """);
        var result = runner("nullguard", "--configuration-cache").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":nullguard").getOutcome());
        String sarif = Files.readString(project.resolve("build/reports/nullguard/nullguard-results.sarif"));
        assertTrue(sarif.contains("Receiver 'missing' may be null"), sarif);
        assertTrue(Files.exists(project.resolve("build/reports/nullguard/nullguard-dashboard-latest.html")));
        assertNull(result.task(":compileJava"));
    }
}
