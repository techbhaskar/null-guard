package com.nullguard.multilang;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** JSON stdin/stdout protocol; logs go to stderr, with time and output limits. */
public final class SubprocessAnalyzer implements LanguageAnalyzer {
  private final PackManager packs;
  private final String id;
  private final Duration timeout;

  public SubprocessAnalyzer(PackManager packs, String id, Duration timeout) {
    this.packs = packs;
    this.id = id;
    this.timeout = timeout;
  }

  @Override
  public ObjectNode analyze(ProjectSpec project) throws Exception {
    JsonNode manifest = packs.manifest(id);
    List<String> command = new ArrayList<>();
    for (JsonNode a : manifest.path("command"))
      command.add(a.asText().replace("${pack}", packs.directory(id).toString()));
    Path work = Files.createTempDirectory("nullguard-pack-run-");
    Process process = null;
    try {
      Path stdout = work.resolve("stdout.json"), stderr = work.resolve("stderr.log");
      Path request = work.resolve("request.json");
      Protocol.JSON.writeValue(request.toFile(), Protocol.request(project));
      ProcessBuilder builder =
          new ProcessBuilder(command)
              .directory(project.root().toFile())
              .redirectInput(request.toFile())
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile());
      for (String key :
          List.of(
              "NODE_OPTIONS",
              "NODE_PATH",
              "PYTHONPATH",
              "PYTHONHOME",
              "DOTNET_STARTUP_HOOKS",
              "DOTNET_ADDITIONAL_DEPS")) builder.environment().remove(key);
      // Tooling must not fetch modules or switch toolchains during a scan.
      builder.environment().put("GOTOOLCHAIN", "local");
      builder.environment().put("GOPROXY", "off");
      builder.environment().put("GOSUMDB", "off");
      builder.environment().put("CARGO_NET_OFFLINE", "true");
      process = builder.start();
      long deadline = System.nanoTime() + timeout.toNanos();
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (System.nanoTime() > deadline)
          throw new IOException("Pack timed out after " + timeout.toSeconds() + " seconds");
        if (Files.size(stdout) > 64L * 1024 * 1024 || Files.size(stderr) > 8L * 1024 * 1024)
          throw new IOException("Pack exceeded output limit");
      }
      if (Files.size(stdout) > 64L * 1024 * 1024 || Files.size(stderr) > 8L * 1024 * 1024)
        throw new IOException("Pack exceeded output limit");
      if (process.exitValue() != 0) {
        String log = Files.readString(stderr);
        throw new IOException(
            "Pack exited "
                + process.exitValue()
                + ": "
                + log.substring(0, Math.min(log.length(), 2000)));
      }
      if (Files.size(stdout) > 64L * 1024 * 1024)
        throw new IOException("Pack exceeded output limit");
      JsonNode result =
          Protocol.JSON
              .reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readTree(Files.readString(stdout));
      return Protocol.validate(result, project);
    } finally {
      if (process != null && process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      PackManager.deleteTree(work);
    }
  }
}
