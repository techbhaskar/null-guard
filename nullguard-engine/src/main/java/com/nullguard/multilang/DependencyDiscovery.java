package com.nullguard.multilang;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Reads resolved metadata only; never restores dependencies or evaluates project scripts. */
public final class DependencyDiscovery {
  private static final ObjectMapper TOML = new TomlMapper(), YAML = new YAMLMapper();
  private final Map<String, ObjectNode> dependencies = new TreeMap<>();
  private final List<String> diagnostics = new ArrayList<>();
  private final List<String> missing = new ArrayList<>();

  public record Result(
      List<ObjectNode> dependencies, List<String> diagnostics, List<String> missing) {}

  public Result discover(ProjectSpec project) {
    Set<String> packs = new HashSet<>();
    project.languages().forEach(l -> packs.add(Language.parse(l).pack));
    if (packs.contains("ecmascript")) javascript(project);
    if (packs.contains("python")) {
      read(project, "poetry.lock", this::pythonLock);
      read(project, "uv.lock", this::pythonLock);
      read(project, "requirements.txt", this::requirements);
    }
    if (packs.contains("csharp")) {
      read(project, "packages.lock.json", this::nugetLock);
      read(project, "obj/project.assets.json", this::nugetAssets);
    }
    if (packs.contains("rust")) read(project, "Cargo.lock", this::cargo);
    if (packs.contains("go")) read(project, "go.mod", this::goMod);
    boolean expects = false;
    try {
      Path js = project.root().resolve("package.json");
      if (packs.contains("ecmascript") && Files.exists(js)) {
        JsonNode p = Protocol.JSON.readTree(js.toFile());
        expects =
            p.path("dependencies").size() > 0
                || p.path("devDependencies").size() > 0
                || p.path("optionalDependencies").size() > 0;
      }
      Path py = project.root().resolve("pyproject.toml");
      if (packs.contains("python") && Files.exists(py)) {
        JsonNode p = TOML.readTree(py.toFile());
        expects =
            p.path("project").path("dependencies").size() > 0
                || p.path("tool").path("poetry").path("dependencies").size() > 0;
      }
      Path rust = project.root().resolve("Cargo.toml");
      if (packs.contains("rust") && Files.exists(rust)) {
        JsonNode p = TOML.readTree(rust.toFile());
        expects =
            p.path("dependencies").size() > 0
                || p.path("workspace").path("dependencies").size() > 0;
      }
      if (packs.contains("csharp"))
        try (var files = Files.list(project.root())) {
          for (Path p : files.filter(x -> x.toString().endsWith(".csproj")).toList())
            if (Files.readString(p).contains("PackageReference")) expects = true;
        }
    } catch (Exception e) {
      missing.add("Cannot inspect declared dependencies: " + e.getMessage());
    }
    if (expects && dependencies.isEmpty())
      missing.add("No resolved dependency versions were available for " + project.id());
    return new Result(
        List.copyOf(dependencies.values()), List.copyOf(diagnostics), List.copyOf(missing));
  }

  @FunctionalInterface
  private interface Reader {
    void read(Path path) throws Exception;
  }

  private void read(ProjectSpec project, String name, Reader reader) {
    Path p = project.root().resolve(name);
    if (Files.isRegularFile(p))
      try {
        reader.read(p);
      } catch (Exception e) {
        missing.add(name + ": " + e.getMessage());
      }
  }

  private void add(String ecosystem, String name, String version, Path source) {
    if (name == null || name.isBlank() || version == null || version.isBlank()) return;
    boolean resolved = version.matches("v?[0-9][0-9A-Za-z._+!~-]*") && !version.contains("*");
    if (!resolved) {
      diagnostics.add(
          "Unresolved version: " + name + " " + version + " in " + source.getFileName());
      return;
    }
    ObjectNode n = Protocol.JSON.createObjectNode();
    n.put("ecosystem", ecosystem);
    n.put("name", name);
    n.put("version", version);
    n.put("source", source.getFileName().toString());
    n.put("resolved", true);
    dependencies.put(ecosystem + ":" + name + ":" + version, n);
  }

  private void npm(Path p) throws Exception {
    JsonNode root = Protocol.JSON.readTree(p.toFile());
    if (root.has("packages")) {
      root.path("packages")
          .fields()
          .forEachRemaining(
              e -> {
                if (e.getKey().isEmpty()) return;
                String name = e.getValue().path("name").asText();
                if (name.isBlank()) {
                  int at = e.getKey().lastIndexOf("node_modules/");
                  if (at >= 0) name = e.getKey().substring(at + 13);
                }
                add("npm", name, e.getValue().path("version").asText(), p);
              });
    } else npmTree(root.path("dependencies"), p);
  }

  private void npmTree(JsonNode node, Path p) {
    node.fields()
        .forEachRemaining(
            e -> {
              add("npm", e.getKey(), e.getValue().path("version").asText(), p);
              npmTree(e.getValue().path("dependencies"), p);
            });
  }

  private void pnpm(Path p) throws Exception {
    JsonNode root = YAML.readTree(p.toFile());
    for (String table : List.of("packages", "snapshots"))
      root.path(table)
          .fieldNames()
          .forEachRemaining(
              key -> {
                String k = key.startsWith("/") ? key.substring(1) : key;
                int peer = k.indexOf('(');
                if (peer >= 0) k = k.substring(0, peer);
                int at = k.lastIndexOf('@');
                if (at > 0) add("npm", k.substring(0, at), k.substring(at + 1), p);
                else {
                  int slash = k.lastIndexOf('/');
                  if (slash > 0) add("npm", k.substring(0, slash), k.substring(slash + 1), p);
                }
              });
  }

  private void javascript(ProjectSpec project) {
    List<String> locks =
        List.of("package-lock.json", "pnpm-lock.yaml", "yarn.lock").stream()
            .filter(n -> Files.isRegularFile(project.root().resolve(n)))
            .toList();
    String manager = "";
    try {
      Path p = project.root().resolve("package.json");
      if (Files.exists(p))
        manager = Protocol.JSON.readTree(p.toFile()).path("packageManager").asText().split("@")[0];
    } catch (Exception e) {
      missing.add("Invalid package.json: " + e.getMessage());
    }
    String preferred =
        switch (manager) {
          case "npm" -> "package-lock.json";
          case "pnpm" -> "pnpm-lock.yaml";
          case "yarn" -> "yarn.lock";
          default -> "";
        };
    if (!preferred.isBlank()) {
      if (!locks.contains(preferred)) {
        missing.add("Selected package manager lockfile is missing: " + preferred);
        return;
      }
      locks = List.of(preferred);
    }
    if (locks.size() > 1) {
      missing.add(
          "Multiple JavaScript lockfiles without an explicit packageManager; no dependency versions"
              + " assumed");
      return;
    }
    for (String lock : locks)
      read(
          project,
          lock,
          switch (lock) {
            case "package-lock.json" -> this::npm;
            case "pnpm-lock.yaml" -> this::pnpm;
            default -> this::yarn;
          });
  }

  private void yarn(Path p) throws Exception {
    String text = Files.readString(p);
    if (text.contains("__metadata:")) {
      JsonNode root = YAML.readTree(text);
      root.fields()
          .forEachRemaining(
              e -> {
                if (e.getKey().equals("__metadata")) return;
                String selector = e.getKey().split(",")[0].trim();
                int at = selector.indexOf('@', selector.startsWith("@") ? 1 : 0);
                if (at > 0)
                  add("npm", selector.substring(0, at), e.getValue().path("version").asText(), p);
              });
      return;
    }
    String name = null;
    Pattern header = Pattern.compile("^\"?((?:@[^/]+/)?[^@\" ,]+)@.*:$");
    Pattern version = Pattern.compile("^\\s+version\\s+\"([^\"]+)\".*$");
    for (String line : text.split("\\R")) {
      Matcher h = header.matcher(line);
      if (h.matches()) name = h.group(1);
      Matcher v = version.matcher(line);
      if (v.matches() && name != null) add("npm", name, v.group(1), p);
    }
  }

  private void pythonLock(Path p) throws Exception {
    JsonNode root = TOML.readTree(p.toFile());
    for (JsonNode n : root.path("package"))
      if (!n.has("source") || !n.path("source").has("editable"))
        add(
            "PyPI",
            n.path("name").asText().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-"),
            n.path("version").asText(),
            p);
  }

  private void requirements(Path p) throws Exception {
    Pattern exact =
        Pattern.compile(
            "^([A-Za-z0-9_.-]+)(?:\\[[^]]+])?==([0-9][A-Za-z0-9_.+!-]*)(?:\\s*;.*)?(?:\\s+#.*)?$");
    for (String line : Files.readAllLines(p)) {
      String text = line.trim();
      if (text.isEmpty() || text.startsWith("#")) continue;
      Matcher m = exact.matcher(text);
      if (m.matches())
        add("PyPI", m.group(1).toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-"), m.group(2), p);
      else diagnostics.add("Unresolved requirements entry: " + text);
    }
  }

  private void nugetLock(Path p) throws Exception {
    JsonNode root = Protocol.JSON.readTree(p.toFile());
    root.path("dependencies")
        .elements()
        .forEachRemaining(
            t ->
                t.fields()
                    .forEachRemaining(
                        e -> add("NuGet", e.getKey(), e.getValue().path("resolved").asText(), p)));
  }

  private void nugetAssets(Path p) throws Exception {
    JsonNode root = Protocol.JSON.readTree(p.toFile());
    root.path("libraries")
        .fields()
        .forEachRemaining(
            e -> {
              if (!e.getValue().path("type").asText().equals("package")) return;
              int slash = e.getKey().lastIndexOf('/');
              if (slash > 0)
                add("NuGet", e.getKey().substring(0, slash), e.getKey().substring(slash + 1), p);
            });
  }

  private void cargo(Path p) throws Exception {
    JsonNode root = TOML.readTree(p.toFile());
    for (JsonNode n : root.path("package"))
      if (n.path("source").asText().startsWith("registry+"))
        add("crates.io", n.path("name").asText(), n.path("version").asText(), p);
  }

  private void goMod(Path p) throws Exception {
    Pattern require =
        Pattern.compile("^(?:require\\s+)?([A-Za-z0-9][^\\s]+)\\s+v([^\\s]+)(?:\\s+//.*)?$");
    boolean block = false;
    for (String line : Files.readAllLines(p)) {
      String t = line.trim();
      if (t.equals("require (")) {
        block = true;
        continue;
      }
      if (t.equals(")")) {
        block = false;
        continue;
      }
      if (t.startsWith("replace ") || t.startsWith("exclude ")) {
        missing.add(
            "Go replace/exclude directives require resolved local module metadata; advisory"
                + " matching omitted");
        continue;
      }
      if (!block && !t.startsWith("require ")) continue;
      Matcher m = require.matcher(t);
      if (m.matches()) add("Go", m.group(1), "v" + m.group(2), p);
    }
    if (missing.stream().anyMatch(x -> x.startsWith("Go replace/exclude")))
      dependencies.entrySet().removeIf(e -> e.getValue().path("ecosystem").asText().equals("Go"));
  }
}
