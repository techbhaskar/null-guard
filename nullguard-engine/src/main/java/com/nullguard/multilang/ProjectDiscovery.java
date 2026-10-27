package com.nullguard.multilang;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Nearest language manifest owns a file; nested projects are not analyzed twice. */
public final class ProjectDiscovery {
  private static final Set<String> EXCLUDED =
      Set.of(
          ".git",
          ".gradle",
          ".idea",
          "node_modules",
          "target",
          "build",
          "dist",
          "out",
          "bin",
          "obj",
          ".venv",
          "venv",
          "env",
          "__pycache__",
          "vendor",
          ".next",
          ".nuxt",
          "coverage",
          "generated",
          ".nullguard",
          "nullguard-out");

  public List<ProjectSpec> discover(Path directory, Set<Language> selected) throws IOException {
    Path root = directory.toRealPath();
    if (!Files.isDirectory(root))
      throw new IllegalArgumentException("Source root must be a directory");
    List<Path> source = new ArrayList<>();
    Map<Path, Set<String>> manifests = new HashMap<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) {
            return !p.equals(root)
                    && (EXCLUDED.contains(p.getFileName().toString()) || Files.isSymbolicLink(p))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path p, BasicFileAttributes a) {
            if (!a.isRegularFile() || Files.isSymbolicLink(p)) return FileVisitResult.CONTINUE;
            String n = p.getFileName().toString();
            Language.forFile(n).filter(selected::contains).ifPresent(l -> source.add(p));
            for (Language l : selected)
              if (isManifest(n, l))
                manifests.computeIfAbsent(p.getParent(), x -> new HashSet<>()).add(l.pack);
            return FileVisitResult.CONTINUE;
          }
        });
    record Key(Path root, String pack) {}
    Map<Key, List<Path>> groups = new HashMap<>();
    for (Path p : source) {
      Language language = Language.forFile(p.getFileName().toString()).orElseThrow();
      Path owner = root;
      for (Path parent = p.getParent();
          parent != null && parent.startsWith(root);
          parent = parent.getParent()) {
        if (manifests.getOrDefault(parent, Set.of()).contains(language.pack)) {
          owner = parent;
          break;
        }
      }
      groups.computeIfAbsent(new Key(owner, language.pack), x -> new ArrayList<>()).add(p);
    }
    // A manifest-only project is visible as empty, rather than silently omitted.
    manifests.forEach(
        (path, packs) ->
            packs.forEach(pack -> groups.putIfAbsent(new Key(path, pack), new ArrayList<>())));
    List<ProjectSpec> result = new ArrayList<>();
    groups.forEach(
        (key, files) -> {
          String relative = root.relativize(key.root()).toString().replace('\\', '/');
          List<String> langs =
              selected.stream()
                  .filter(l -> l.pack.equals(key.pack()))
                  .filter(
                      l ->
                          files.isEmpty()
                              || files.stream()
                                  .anyMatch(
                                      p ->
                                          Language.forFile(p.getFileName().toString()).orElseThrow()
                                              == l))
                  .map(l -> l.id)
                  .sorted()
                  .toList();
          result.add(
              new ProjectSpec(
                  (relative.isEmpty() ? "." : relative) + ":" + key.pack(),
                  key.root(),
                  langs,
                  files.stream()
                      .map(p -> key.root().relativize(p).toString().replace('\\', '/'))
                      .sorted()
                      .toList()));
        });
    return result.stream().sorted(Comparator.comparing(ProjectSpec::id)).toList();
  }

  private static boolean isManifest(String n, Language l) {
    return switch (l) {
      case JAVA -> Set.of("pom.xml", "build.gradle", "build.gradle.kts").contains(n);
      case PYTHON ->
          Set.of("pyproject.toml", "requirements.txt", "setup.cfg", "setup.py").contains(n);
      case JAVASCRIPT, TYPESCRIPT ->
          Set.of("package.json", "tsconfig.json", "jsconfig.json").contains(n);
      case CSHARP -> n.endsWith(".csproj");
      case GO -> n.equals("go.mod");
      case RUST -> n.equals("Cargo.toml");
    };
  }
}
