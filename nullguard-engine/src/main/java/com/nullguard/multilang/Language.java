package com.nullguard.multilang;

import java.util.*;

/** Stable protocol identifiers; frameworks are deliberately not languages. */
public enum Language {
  JAVA("java", "java", Set.of("java")),
  PYTHON("python", "python", Set.of("py")),
  JAVASCRIPT("javascript", "ecmascript", Set.of("js", "jsx", "mjs", "cjs")),
  TYPESCRIPT("typescript", "ecmascript", Set.of("ts", "tsx", "mts", "cts")),
  CSHARP("csharp", "csharp", Set.of("cs")),
  GO("go", "go", Set.of("go")),
  RUST("rust", "rust", Set.of("rs"));
  public final String id, pack;
  private final Set<String> extensions;

  Language(String id, String pack, Set<String> extensions) {
    this.id = id;
    this.pack = pack;
    this.extensions = extensions;
  }

  public static Language parse(String id) {
    return Arrays.stream(values())
        .filter(l -> l.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown language: " + id));
  }

  public static Optional<Language> forFile(String name) {
    int dot = name.lastIndexOf('.');
    String ext = dot < 0 ? "" : name.substring(dot + 1);
    return Arrays.stream(values()).filter(l -> l.extensions.contains(ext)).findFirst();
  }
}
