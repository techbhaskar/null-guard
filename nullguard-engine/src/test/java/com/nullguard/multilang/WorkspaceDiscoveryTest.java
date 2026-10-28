package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDiscoveryTest {
  @TempDir Path root;

  void write(String path, String text) throws Exception {
    var file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void pureWorkspaceManifestsDoNotFailMixedSourceProjects() throws Exception {
    write(
        "pom.xml",
        "<project><packaging>pom</packaging><modules><module>j</module></modules></project>");
    write("j/pom.xml", "<project/>");
    write("j/A.java", "class A{}");
    write("package.json", "{\"workspaces\":[\"web\"]}");
    write("web/package.json", "{}");
    write("web/index.ts", "export const x=1");
    write("Cargo.toml", "[workspace]\nmembers=['rust']\n");
    write("rust/Cargo.toml", "[package]\nname='app'\n");
    write("rust/src/main.rs", "fn main(){}");
    write("pyproject.toml", "[tool.uv.workspace]\nmembers=['python']\n");
    write("python/pyproject.toml", "[project]\nname='app'\n");
    write("python/app.py", "x=1");
    var projects =
        new ProjectDiscovery()
            .discover(
                root, Set.of(Language.JAVA, Language.TYPESCRIPT, Language.RUST, Language.PYTHON));
    assertEquals(4, projects.size());
    assertTrue(projects.stream().noneMatch(p -> p.files().isEmpty()));
  }

  @Test
  void realEmptyAndMalformedProjectsRemainVisible() throws Exception {
    write("package.json", "{\"workspaces\":123}");
    write("web/package.json", "{}");
    write("web/index.js", "x=1");
    var projects = new ProjectDiscovery().discover(root, Set.of(Language.JAVASCRIPT));
    assertEquals(2, projects.size());
    assertTrue(projects.stream().anyMatch(p -> p.files().isEmpty()));
    write("package.json", "not json");
    assertFalse(WorkspaceMetadata.aggregate(root, "ecmascript"));
  }

  @Test
  void solutionReferencesSupportJsonCommentsButMissingReferencesAreNotOmitted() throws Exception {
    write("web/tsconfig.json", "{}");
    write("web/index.ts", "export {};");
    write("tsconfig.json", "{/* solution */\"files\":[],\"references\":[{\"path\":\"web\"},],}");
    assertEquals(1, new ProjectDiscovery().discover(root, Set.of(Language.TYPESCRIPT)).size());
    write("tsconfig.json", "{\"references\":[{\"path\":\"missing\"}]}");
    assertEquals(2, new ProjectDiscovery().discover(root, Set.of(Language.TYPESCRIPT)).size());
    write("tsconfig.json", "{\"references\":[{\"path\":13}]}");
    assertFalse(WorkspaceMetadata.aggregate(root, "ecmascript"));
  }

  @Test
  void ordinaryPackagesAndMetadataWithoutDescendantSourceAreNotSilentlyDropped() throws Exception {
    write(
        "pom.xml",
        "<project><packaging>pom</packaging><modules><module>empty</module></modules></project>");
    assertEquals(1, new ProjectDiscovery().discover(root, Set.of(Language.JAVA)).size());
    write("Cargo.toml", "[workspace]\nmembers=['x']\n[package]\nname='root'\n");
    assertFalse(WorkspaceMetadata.aggregate(root, "rust"));
    write("pyproject.toml", "[project]\nname='root'\n[tool.uv.workspace]\nmembers=['child']\n");
    assertFalse(WorkspaceMetadata.aggregate(root, "python"));
    assertFalse(WorkspaceMetadata.aggregate(root, "go"));
    assertFalse(WorkspaceMetadata.aggregate(root, "csharp"));
  }

  @Test
  void malformedXmlAndTomlDoNotBecomeCleanWorkspaceScans() throws Exception {
    write("pom.xml", "<project>");
    assertFalse(WorkspaceMetadata.aggregate(root, "java"));
    write("Cargo.toml", "[[bad");
    assertFalse(WorkspaceMetadata.aggregate(root, "rust"));
    write("pyproject.toml", "[[bad");
    assertFalse(WorkspaceMetadata.aggregate(root, "python"));
  }
}
