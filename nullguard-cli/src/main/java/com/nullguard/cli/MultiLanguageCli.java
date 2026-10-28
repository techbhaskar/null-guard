package com.nullguard.cli;

import com.nullguard.bootstrap.NullGuardConfig;
import com.nullguard.multilang.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Opt-in entrypoint: legacy Java options continue through the existing CLI. */
final class MultiLanguageCli {
  private MultiLanguageCli() {}

  static boolean handles(String[] args) {
    return args.length > 0
        && (args[0].equals("packs")
            || Arrays.stream(args).anyMatch(a -> a.startsWith("--languages=")));
  }

  static int run(String[] args) {
    try {
      PackManager packs = PackManager.standard();
      if (args[0].equals("packs")) return manage(args, packs);
      Path root = null, output = Path.of("nullguard-out"), cve = null;
      boolean online = false;
      Duration timeout = Duration.ofMinutes(10);
      Set<Language> languages = new LinkedHashSet<>();
      List<Path> classpath = new ArrayList<>();
      for (String arg : args) {
        if (arg.startsWith("--languages=")) {
          String value = arg.substring(12);
          if (value.equals("auto")) languages.addAll(List.of(Language.values()));
          else for (String l : value.split(",", -1)) languages.add(Language.parse(l));
        } else if (arg.startsWith("--output=")) output = Path.of(arg.substring(9));
        else if (arg.startsWith("--timeout=")) {
          long seconds = Long.parseLong(arg.substring(10));
          if (seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("Timeout must be 1..86400 seconds");
          timeout = Duration.ofSeconds(seconds);
        } else if (arg.startsWith("--cve-input=")) cve = Path.of(arg.substring(12));
        else if (arg.equals("--cve-online")) online = true;
        else if (arg.startsWith("--classpath=")) {
          for (String p :
              arg.substring(12).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            if (!p.isBlank()) classpath.add(Path.of(p));
        } else if (arg.startsWith("--"))
          throw new IllegalArgumentException(
              "Unsupported multi-language option: " + arg + "; preview findings are report-only");
        else if (root == null) root = Path.of(arg);
        else throw new IllegalArgumentException("Multiple source roots supplied");
      }
      if (root == null || !Files.isDirectory(root) || languages.isEmpty())
        throw new IllegalArgumentException(
            "Specify a source directory and --languages=auto or comma-separated languages");
      var report =
          new MultiLanguageRunner(packs, timeout)
              .run(
                  root,
                  languages,
                  NullGuardConfig.defaults().classpathEntries(classpath).build(),
                  cve,
                  online);
      new MultiLanguageReports().write(report, output);
      System.out.println(
          "NullGuard multi-language preview: "
              + report.path("status").asText()
              + "; reports: "
              + output.toAbsolutePath());
      return report.path("status").asText().equals("complete") ? 0 : 3;
    } catch (IllegalArgumentException e) {
      System.err.println("[NullGuard] " + e.getMessage());
      return 2;
    } catch (Exception e) {
      System.err.println("[NullGuard] Multi-language error: " + e.getMessage());
      return 3;
    }
  }

  private static int manage(String[] args, PackManager packs) throws Exception {
    if (args.length == 2 && args[1].equals("list")) {
      for (String id : PackManager.IDS.stream().sorted().toList()) {
        try {
          System.out.println(id + " " + packs.manifest(id).path("version").asText());
        } catch (java.io.IOException e) {
          System.out.println(id + " not installed (" + packs.directory(id) + ")");
        }
      }
      return 0;
    }
    if (args.length < 3 || !Set.of("install", "remove").contains(args[1]))
      throw new IllegalArgumentException(
          "Usage: packs list | packs install <ids> [--from=archive.zip] | packs remove <ids>");
    Path local = null;
    List<String> ids = new ArrayList<>();
    for (int i = 2; i < args.length; i++) {
      if (args[i].startsWith("--from=")) local = Path.of(args[i].substring(7));
      else ids.add(args[i]);
    }
    if (local != null && (ids.size() != 1 || !args[1].equals("install")))
      throw new IllegalArgumentException("--from requires exactly one pack install");
    if (ids.isEmpty()) throw new IllegalArgumentException("No pack selected");
    for (String id : ids) {
      if (args[1].equals("install")) packs.install(id, local);
      else packs.remove(id);
      System.out.println(args[1] + " completed: " + id);
    }
    return 0;
  }
}
