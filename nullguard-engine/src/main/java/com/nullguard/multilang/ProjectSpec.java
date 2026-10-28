package com.nullguard.multilang;

import java.nio.file.Path;
import java.util.List;

public record ProjectSpec(String id, Path root, List<String> languages, List<String> files) {
  public ProjectSpec {
    root = root.toAbsolutePath().normalize();
    languages = List.copyOf(languages);
    files = List.copyOf(files);
  }
}
