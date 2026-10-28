package com.nullguard.multilang;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AdvisoryAndDistributionTest {
  @TempDir Path root;

  ObjectNode project() {
    var p = Protocol.empty(new ProjectSpec("p", root, List.of("python"), List.of()), "python");
    p.withArray("dependencies")
        .addObject()
        .put("ecosystem", "PyPI")
        .put("name", "example")
        .put("version", "1.2.3");
    return p;
  }

  @Test
  void osvBatchHasExactInstalledVersionAndRetainsNoReachabilityClaim() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var query = Protocol.JSON.readTree(exchange.getRequestBody());
          assertEquals("1.2.3", query.path("queries").get(0).path("version").asText());
          byte[] bytes =
              "{\"results\":[{\"vulns\":[{\"id\":\"OSV-1\"},{\"id\":\"OSV-1\"}]}]}".getBytes();
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var p = project();
      new AdvisoryMatcher(URI.create("http://localhost:" + server.getAddress().getPort() + "/"))
          .enrich(p, null, true);
      assertEquals(1, p.path("vulnerabilities").size());
      assertEquals("Not available", p.path("vulnerabilityReachability").asText());
      p.put("root", root.toString());
      var report = Protocol.JSON.createObjectNode();
      report.putArray("projects").add(p);
      assertEquals(
          "NG-DEP-OSV-1",
          new MultiLanguageReports()
              .sarif(report)
              .path("runs")
              .get(0)
              .path("results")
              .get(0)
              .path("ruleId")
              .asText());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void osvHttpFailureAndMalformedBatchAreErrors() throws Exception {
    for (int code : List.of(500, 200)) {
      var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/",
          e -> {
            e.getRequestBody().readAllBytes();
            byte[] b = "{\"results\":[]}".getBytes();
            e.sendResponseHeaders(code, b.length);
            e.getResponseBody().write(b);
            e.close();
          });
      server.start();
      try {
        assertThrows(
            Exception.class,
            () ->
                new AdvisoryMatcher(URI.create("http://localhost:" + server.getAddress().getPort()))
                    .enrich(project(), null, true));
      } finally {
        server.stop(0);
      }
    }
  }

  @Test
  void offlineOsvAndTrivyMatchResolvedVersionsAndExplainUnevaluatedRanges() throws Exception {
    Path input = root.resolve("advisories.json");
    Files.writeString(
        input,
        """
{"vulnerabilities":[{"id":"OSV-1","affected":[{"package":{"ecosystem":"PyPI","name":"example"},"versions":["1.2.3"],"ranges":[{}]}]}],"Results":[{"Vulnerabilities":[{"VulnerabilityID":"TRIVY-1","PkgName":"example","InstalledVersion":"1.2.3","Title":"test advisory"}]}]}
""");
    var p = project();
    new AdvisoryMatcher().enrich(p, input, false);
    assertEquals(2, p.path("vulnerabilities").size());
    assertFalse(p.path("diagnostics").isEmpty());
    assertThrows(
        Exception.class, () -> new AdvisoryMatcher().enrich(p, root.resolve("absent.json"), false));
  }

  @Test
  void downloadUsesSameSignedInstallerAndHttpFailuresDoNotInstall() throws Exception {
    boolean gpg;
    try {
      gpg = new ProcessBuilder("gpgv", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      gpg = false;
    }
    Assumptions.assumeTrue(gpg);
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        e -> {
          String name = e.getRequestURI().getPath();
          String suffix =
              name.endsWith(".sha256") ? ".sha256" : name.endsWith(".asc") ? ".asc" : "";
          try (var in = getClass().getResourceAsStream("/multilang/fixture.zip" + suffix)) {
            byte[] b = in.readAllBytes();
            e.sendResponseHeaders(name.contains("rust") ? 404 : 200, b.length);
            e.getResponseBody().write(b);
          }
          e.close();
        });
    server.start();
    try {
      var packs =
          new PackManager(
              root.resolve("packs"),
              URI.create("http://localhost:" + server.getAddress().getPort() + "/"));
      packs.install("python", null);
      assertEquals("python", packs.manifest("python").path("id").asText());
      assertThrows(Exception.class, () -> packs.install("rust", null));
      assertFalse(Files.exists(packs.directory("rust")));
    } finally {
      server.stop(0);
    }
  }
}
