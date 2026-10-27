package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyMetadataTest {
  @TempDir Path root;

  void write(String name, String value) throws Exception {
    Path p = root.resolve(name);
    Files.createDirectories(p.getParent());
    Files.writeString(p, value);
  }

  DependencyDiscovery.Result scan(String lang) {
    return new DependencyDiscovery().discover(new ProjectSpec("p", root, List.of(lang), List.of()));
  }

  @Test
  void oldNpmTreeAndYarnBerryRemainEcosystemSpecific() throws Exception {
    write("package.json", "{\"packageManager\":\"npm@10\",\"dependencies\":{\"a\":\"^1\"}}");
    assertFalse(scan("typescript").missing().isEmpty());
    write(
        "package-lock.json",
        "{\"dependencies\":{\"a\":{\"version\":\"1.0.0\",\"dependencies\":{\"b\":{\"version\":\"2.0.0\"}}}}}");
    assertEquals(2, scan("typescript").dependencies().size());
    write("package.json", "{\"packageManager\":\"yarn@4\"}");
    write("yarn.lock", "__metadata:\n  version: 8\n\"@scope/pkg@npm:^1\":\n  version: 1.2.3\n");
    assertEquals("@scope/pkg", scan("javascript").dependencies().getFirst().path("name").asText());
  }

  @Test
  void poetryUvNugetAssetsAndGoReplacementsReportOnlyResolvedVersions() throws Exception {
    write("pyproject.toml", "[project]\nname='test'\ndependencies=['example>=1']\n");
    assertFalse(scan("python").missing().isEmpty());
    write("poetry.lock", "[[package]]\nname='Example'\nversion='1.2.3'\n");
    write(
        "uv.lock", "[[package]]\nname='local'\nversion='1.0.0'\n[package.source]\neditable='.'\n");
    assertEquals(1, scan("python").dependencies().size());
    write(
        "app.csproj",
        "<Project><ItemGroup><PackageReference Include='Example'"
            + " Version='1.2.3'/></ItemGroup></Project>");
    assertFalse(scan("csharp").missing().isEmpty());
    write(
        "obj/project.assets.json",
        "{\"libraries\":{\"Example/1.2.3\":{\"type\":\"package\"},\"Local/1.0\":{\"type\":\"project\"}}}");
    assertEquals(1, scan("csharp").dependencies().size());
    write(
        "go.mod",
        "module example.com/test\n"
            + "require (\n"
            + " example.com/dep v1.2.3 // indirect\n"
            + ")\n"
            + "replace example.com/dep => ../dep\n");
    assertTrue(scan("go").dependencies().isEmpty());
    assertFalse(scan("go").missing().isEmpty());
    write("Cargo.toml", "[package]\nname='test'\n[dependencies]\nserde='1'\n");
    assertFalse(scan("rust").missing().isEmpty());
  }

  @Test
  void malformedMetadataIsAnExplicitGap() throws Exception {
    write("package.json", "not json");
    write("package-lock.json", "not json");
    assertFalse(scan("typescript").missing().isEmpty());
    write("pyproject.toml", "[[invalid");
    assertFalse(scan("python").missing().isEmpty());
  }
}
