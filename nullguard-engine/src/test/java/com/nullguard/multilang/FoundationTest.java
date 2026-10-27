package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationTest {
  @TempDir Path root;

  void write(String name, String text) throws Exception {
    Path p = root.resolve(name);
    Files.createDirectories(p.getParent());
    Files.writeString(p, text);
  }

  ProjectSpec project(String lang) {
    return new ProjectSpec("fixture", root, List.of(lang), List.of());
  }

  @Test
  void nearestManifestOwnsFilesAndExcludedDirectoriesAreNotScanned() throws Exception {
    write("package.json", "{}");
    write("web/package.json", "{}");
    write("a.ts", "const x=null");
    write("web/App.tsx", "const x=1");
    write("node_modules/dep/index.js", "bad");
    write(".venv/dep.py", "bad");
    write("backend/pyproject.toml", "[project]\nname='api'");
    write("backend/app.py", "x=None");
    var result =
        new ProjectDiscovery()
            .discover(root, Set.of(Language.TYPESCRIPT, Language.PYTHON, Language.JAVASCRIPT));
    assertEquals(3, result.size());
    assertEquals(3, result.stream().mapToInt(p -> p.files().size()).sum());
    assertTrue(
        result.stream()
            .noneMatch(p -> p.files().stream().anyMatch(f -> f.contains("node_modules"))));
  }

  @Test
  void allLockFormatsRetainResolvedVersions() throws Exception {
    write(
        "package-lock.json",
        "{\"packages\":{\"node_modules/@scope/pkg\":{\"version\":\"1.2.3\"}}}");
    assertEquals(
        "@scope/pkg",
        new DependencyDiscovery()
            .discover(project("typescript"))
            .dependencies()
            .getFirst()
            .path("name")
            .asText());
    Files.delete(root.resolve("package-lock.json"));
    write("pnpm-lock.yaml", "packages:\n  '@scope/pkg@1.2.3(peer@2.0.0)': {}\n");
    assertEquals(
        "1.2.3",
        new DependencyDiscovery()
            .discover(project("typescript"))
            .dependencies()
            .getFirst()
            .path("version")
            .asText());
    Files.delete(root.resolve("pnpm-lock.yaml"));
    write("yarn.lock", "# yarn lockfile v1\n\"@scope/pkg@^1.0.0\":\n  version \"1.2.3\"\n");
    assertEquals(
        "@scope/pkg",
        new DependencyDiscovery()
            .discover(project("javascript"))
            .dependencies()
            .getFirst()
            .path("name")
            .asText());
    write("requirements.txt", "Some_Pkg==1.2.3\nunknown>=2\n");
    var py = new DependencyDiscovery().discover(project("python"));
    assertEquals("some-pkg", py.dependencies().getFirst().path("name").asText());
    assertFalse(py.diagnostics().isEmpty());
    write(
        "Cargo.lock",
        "version = 4\n"
            + "[[package]]\n"
            + "name='serde'\n"
            + "version='1.0.0'\n"
            + "source='registry+https://github.com/rust-lang/crates.io-index'\n");
    assertEquals(
        "crates.io",
        new DependencyDiscovery()
            .discover(project("rust"))
            .dependencies()
            .getFirst()
            .path("ecosystem")
            .asText());
    write("go.mod", "module example.com/fixture\ngo 1.26\nrequire example.com/dep v1.2.3\n");
    assertEquals(
        "v1.2.3",
        new DependencyDiscovery()
            .discover(project("go"))
            .dependencies()
            .getFirst()
            .path("version")
            .asText());
    write(
        "packages.lock.json",
        "{\"dependencies\":{\"net10.0\":{\"Example\":{\"resolved\":\"1.2.3\"}}}}");
    assertEquals(
        "NuGet",
        new DependencyDiscovery()
            .discover(project("csharp"))
            .dependencies()
            .getFirst()
            .path("ecosystem")
            .asText());
  }

  @Test
  void ambiguousLockfilesAndMissingVersionsDoNotInventDependencies() throws Exception {
    write("package.json", "{\"dependencies\":{\"pkg\":\"^1\"}}");
    write("package-lock.json", "{}");
    write("yarn.lock", "{}");
    var r = new DependencyDiscovery().discover(project("typescript"));
    assertFalse(r.missing().isEmpty());
    assertTrue(r.dependencies().isEmpty());
  }

  @Test
  void offlineAdvisoriesRequireExactInstalledVersionAndNoReachabilityClaim() throws Exception {
    var p = Protocol.empty(project("python"), "python");
    p.withArray("dependencies")
        .addObject()
        .put("ecosystem", "PyPI")
        .put("name", "example")
        .put("version", "1.2.3");
    write(
        "cve.json",
        "{\"vulnerabilities\":[{\"id\":\"TEST-1\",\"ecosystem\":\"PyPI\",\"name\":\"example\",\"version\":\"1.2.3\"},{\"id\":\"TEST-2\",\"name\":\"example\",\"version\":\"2.0.0\"}]}");
    new AdvisoryMatcher().enrich(p, root.resolve("cve.json"), false);
    assertEquals(1, p.path("vulnerabilities").size());
    assertEquals("Not available", p.path("vulnerabilityReachability").asText());
  }

  @Test
  void missingPackFailsOneProjectButKeepsReportsForOthers() throws Exception {
    write("a.py", "x=None");
    write("a.ts", "const x=null");
    var report =
        new MultiLanguageRunner(new PackManager(root.resolve("packs")), Duration.ofSeconds(1))
            .run(
                root,
                Set.of(Language.PYTHON, Language.TYPESCRIPT),
                com.nullguard.bootstrap.NullGuardConfig.defaults().build(),
                null,
                false);
    assertEquals(2, report.path("projects").size());
    assertEquals("partial", report.path("status").asText());
    new MultiLanguageReports().write(report, root.resolve("reports"));
    assertTrue(Files.exists(root.resolve("reports/nullguard-results.sarif")));
    assertTrue(
        Files.readString(root.resolve("reports/nullguard-dashboard-latest.html"))
            .contains("Not available"));
  }

  @Test
  void strictProtocolRejectsOmittedFilesAndInvalidLocations() {
    var p = new ProjectSpec("test", root, List.of("python"), List.of("a.py"));
    var reply = Protocol.failed(p, "failure");
    assertDoesNotThrow(() -> Protocol.validate(reply, p));
    reply.withObject("/coverage/python").put("discoveredFiles", 0);
    assertThrows(IllegalArgumentException.class, () -> Protocol.validate(reply, p));
  }
}
