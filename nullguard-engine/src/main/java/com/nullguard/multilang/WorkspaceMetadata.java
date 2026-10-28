package com.nullguard.multilang;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.nio.file.*;
import javax.xml.stream.*;

/** Reads virtual-workspace metadata without invoking a build tool or generators. */
final class WorkspaceMetadata {
  private WorkspaceMetadata() {}

  private static boolean present(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
  }

  static boolean aggregate(Path root, String pack) {
    try {
      switch (pack) {
        case "java":
          Path pom = root.resolve("pom.xml");
          if (!present(pom)) return false;
          var factory = XMLInputFactory.newFactory();
          factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
          factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
          boolean packaging = false, modules = false, inModules = false;
          try (var input = Files.newInputStream(pom)) {
            var xml = factory.createXMLStreamReader(input);
            try {
              while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                  String name = xml.getLocalName();
                  if (name.equals("packaging"))
                    packaging = xml.getElementText().trim().equals("pom");
                  if (name.equals("modules")) inModules = true;
                  if (inModules && name.equals("module"))
                    modules |= !xml.getElementText().isBlank();
                } else if (event == XMLStreamConstants.END_ELEMENT
                    && xml.getLocalName().equals("modules")) inModules = false;
              }
            } finally {
              xml.close();
            }
          }
          return packaging && modules;
        case "ecmascript":
          Path pkg = root.resolve("package.json");
          if (present(pkg)) {
            var workspaces = Protocol.JSON.readTree(pkg.toFile()).path("workspaces");
            if (strings(workspaces) || strings(workspaces.path("packages"))) return true;
          }
          for (String name : new String[] {"tsconfig.json", "jsconfig.json"}) {
            Path config = root.resolve(name);
            if (!present(config)) continue;
            var json =
                new ObjectMapper()
                    .enable(JsonParser.Feature.ALLOW_COMMENTS)
                    .enable(JsonParser.Feature.ALLOW_TRAILING_COMMA)
                    .readTree(config.toFile());
            var references = json.path("references");
            if (references.isArray() && !references.isEmpty()) {
              boolean valid = true;
              for (var reference : references) {
                var path = reference.path("path");
                valid &=
                    path.isTextual()
                        && !path.asText().isBlank()
                        && Files.exists(root.resolve(path.asText()));
              }
              if (valid) return true;
            }
          }
          return false;
        case "rust":
          Path cargo = root.resolve("Cargo.toml");
          if (!present(cargo)) return false;
          var rust = new TomlMapper().readTree(cargo.toFile());
          return rust.path("workspace").isObject() && !rust.has("package");
        case "python":
          Path pyproject = root.resolve("pyproject.toml");
          if (!present(pyproject)) return false;
          var python = new TomlMapper().readTree(pyproject.toFile());
          return !python.has("project")
              && strings(python.path("tool").path("uv").path("workspace").path("members"));
        default:
          return false;
      }
    } catch (Exception malformed) {
      return false;
    }
  }

  private static boolean strings(JsonNode items) {
    if (!items.isArray() || items.isEmpty()) return false;
    for (var item : items) if (!item.isTextual() || item.asText().isBlank()) return false;
    return true;
  }
}
