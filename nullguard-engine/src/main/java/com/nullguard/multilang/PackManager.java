package com.nullguard.multilang;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Explicit pack installation. Scans never install or update executable code. */
public final class PackManager {
  public static final Set<String> IDS = Set.of("python", "ecmascript", "csharp", "go", "rust");
  public static final String SIGNER = "440E91AFE2B4E7291DFBB4BCF03390620AF752E8";
  private final Path home;
  private final URI releaseBase;

  public PackManager(Path home) {
    this(
        home,
        URI.create(
            "https://github.com/techbhaskar/null-guard/releases/download/v"
                + Protocol.ANALYZER_VERSION
                + "/"));
  }

  PackManager(Path home, URI releaseBase) {
    this.home = home.toAbsolutePath().normalize();
    this.releaseBase = releaseBase;
  }

  public static PackManager standard() {
    String override = System.getenv("NULLGUARD_PACK_HOME");
    if (override != null && !override.isBlank()) return new PackManager(Path.of(override));
    String data = System.getenv("XDG_DATA_HOME"), win = System.getenv("LOCALAPPDATA");
    return new PackManager(
        System.getProperty("os.name").startsWith("Windows") && win != null
            ? Path.of(win, "NullGuard", "packs")
            : data != null
                ? Path.of(data, "nullguard", "packs")
                : Path.of(System.getProperty("user.home"), ".nullguard", "packs"));
  }

  public Path home() {
    return home;
  }

  public Path directory(String id) {
    checkId(id);
    return home.resolve(id);
  }

  public JsonNode manifest(String id) throws IOException {
    JsonNode m = Protocol.JSON.readTree(directory(id).resolve("pack.json").toFile());
    if (!id.equals(m.path("id").asText())
        || !Protocol.VERSION.equals(m.path("protocolVersion").asText())
        || !Protocol.ANALYZER_VERSION.equals(m.path("version").asText())
        || !m.path("command").isArray()
        || m.path("command").isEmpty()) throw new IOException("Incompatible pack manifest: " + id);
    return m;
  }

  public void remove(String id) throws IOException {
    deleteTree(directory(id));
  }

  public void install(String id, Path localArchive) throws Exception {
    checkId(id);
    if (Files.exists(directory(id)))
      throw new IllegalArgumentException(
          "Pack already installed; remove it explicitly before installing another version: " + id);
    Files.createDirectories(home);
    Path work = Files.createTempDirectory(home, ".install-");
    try {
      Path zip = work.resolve("pack.zip"),
          sig = work.resolve("pack.zip.asc"),
          sha = work.resolve("pack.zip.sha256");
      if (localArchive != null) {
        Files.copy(localArchive, zip);
        Files.copy(Path.of(localArchive + ".asc"), sig);
        Files.copy(Path.of(localArchive + ".sha256"), sha);
      } else {
        String platform = platform();
        String asset = "nullguard-pack-" + id + "-" + platform + ".zip";
        String base = releaseBase.resolve(asset).toString();
        download(base, zip);
        download(base + ".asc", sig);
        download(base + ".sha256", sha);
      }
      String expected = Files.readString(sha).trim().split("\\s+")[0];
      String actual =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
      if (!expected.matches("[0-9a-fA-F]{64}") || !expected.equalsIgnoreCase(actual))
        throw new IOException("Pack checksum mismatch");
      verify(zip, sig, work);
      Path extracted = work.resolve("unpacked");
      Files.createDirectory(extracted);
      long expanded = 0;
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
        ZipEntry entry;
        byte[] buffer = new byte[16384];
        int count = 0;
        while ((entry = in.getNextEntry()) != null) {
          if (++count > 30000) throw new IOException("Pack contains too many entries");
          Path target = extracted.resolve(entry.getName()).normalize();
          if (entry.getName().contains("\\")
              || !target.startsWith(extracted)
              || target.equals(extracted)) throw new IOException("Unsafe pack archive path");
          if (entry.isDirectory()) {
            Files.createDirectories(target);
            continue;
          }
          Files.createDirectories(target.getParent());
          try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
              expanded += n;
              if (expanded > 512L * 1024 * 1024)
                throw new IOException("Pack exceeds expanded size limit");
              out.write(buffer, 0, n);
            }
          }
        }
      }
      JsonNode m = Protocol.JSON.readTree(extracted.resolve("pack.json").toFile());
      if (!id.equals(m.path("id").asText())
          || !Protocol.VERSION.equals(m.path("protocolVersion").asText())
          || !Protocol.ANALYZER_VERSION.equals(m.path("version").asText()))
        throw new IOException("Archive manifest mismatch");
      for (JsonNode arg : m.path("command"))
        if (arg.asText().startsWith("${pack}/")) {
          Path entry = extracted.resolve(arg.asText().substring(8)).normalize();
          if (!entry.startsWith(extracted) || !Files.isRegularFile(entry))
            throw new IOException("Invalid pack entrypoint");
          entry.toFile().setExecutable(true, true);
        }
      Files.move(extracted, directory(id), StandardCopyOption.ATOMIC_MOVE);
    } finally {
      deleteTree(work);
    }
  }

  private static void verify(Path zip, Path sig, Path work) throws Exception {
    Path pub = work.resolve("release-key.asc"),
        keyring = work.resolve("release-key.gpg"),
        log = work.resolve("gpg.log");
    try (InputStream in = PackManager.class.getResourceAsStream("/multilang/release-key.asc")) {
      if (in == null) throw new IOException("Pack trust key is missing");
      Files.copy(in, pub);
    }
    // gpgv needs no agent, private keyring or trust database. This also avoids Unix
    // socket path limits when the user's pack directory is deeply nested.
    Process importer =
        new ProcessBuilder(
                "gpg",
                "--batch",
                "--yes",
                "--dearmor",
                "--output",
                keyring.toString(),
                pub.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    if (!importer.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || importer.exitValue() != 0) {
      importer.destroyForcibly();
      throw new IOException("Cannot prepare pack trust key: " + Files.readString(log));
    }
    Process verify =
        new ProcessBuilder(
                "gpgv",
                "--status-fd",
                "1",
                "--keyring",
                keyring.toString(),
                sig.toString(),
                zip.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    if (!verify.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        || verify.exitValue() != 0
        || !Files.readString(log).contains("VALIDSIG " + SIGNER)) {
      verify.destroyForcibly();
      throw new IOException("Pack signature is not valid for the trusted release key");
    }
  }

  private static void download(String url, Path target) throws Exception {
    HttpClient client =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream in = response.body();
        OutputStream out = Files.newOutputStream(target)) {
      if (response.statusCode() != 200)
        throw new IOException(
            "Pack asset unavailable (HTTP " + response.statusCode() + "): " + url);
      long n = 0;
      byte[] b = new byte[16384];
      int read;
      while ((read = in.read(b)) != -1) {
        n += read;
        if (n > 256L * 1024 * 1024) throw new IOException("Pack download exceeds limit");
        out.write(b, 0, read);
      }
    }
  }

  public static String platform() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
    String family =
        os.contains("win")
            ? "windows"
            : os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : "unsupported";
    String cpu =
        Set.of("arm64", "aarch64").contains(arch)
            ? "arm64"
            : Set.of("amd64", "x86_64").contains(arch) ? "x64" : "unsupported";
    if (family.equals("unsupported") || cpu.equals("unsupported"))
      throw new IllegalArgumentException("Unsupported pack platform");
    return family + "-" + cpu;
  }

  private static void checkId(String id) {
    if (!IDS.contains(id)) throw new IllegalArgumentException("Unknown pack: " + id);
  }

  static void deleteTree(Path p) throws IOException {
    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return;
    try (var files = Files.walk(p)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
    }
  }
}
