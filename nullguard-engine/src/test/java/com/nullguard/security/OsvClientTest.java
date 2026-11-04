package com.nullguard.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OsvClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RECORD = """
        {"id":"GHSA-new-2026","published":"2026-10-01T00:00:00Z","modified":"2026-11-04T00:00:00Z",
         "aliases":["CVE-2026-12345"],"summary":"A newly published advisory",
         "database_specific":{"severity":"MODERATE"},
         "severity":[{"type":"CVSS_V3","score":"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H"}],
         "affected":[{"package":{"ecosystem":"Maven","name":"demo:lib"},
                      "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.1"}]}]}]}
        """;

    @Test void followsPerPackagePaginationAndHydratesRecentRecordsOnce() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var requests = new AtomicInteger();
        var details = new AtomicInteger();
        server.createContext("/v1/querybatch", exchange -> {
            var queries = JSON.readTree(exchange.getRequestBody().readAllBytes()).path("queries");
            int call = requests.incrementAndGet();
            if (call == 1) assertEquals(2, queries.size());
            else {
                assertEquals(1, queries.size());
                assertEquals("next", queries.get(0).path("page_token").asText());
                assertEquals("demo:lib", queries.get(0).path("package").path("name").asText());
            }
            send(exchange, 200, call == 1
                ? "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-new-2026\"}],\"next_page_token\":\"next\"},{}]}"
                : "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-new-2026\"}]}]}");
        });
        server.createContext("/v1/vulns/GHSA-new-2026", exchange -> {
            details.incrementAndGet(); send(exchange, 200, RECORD);
        });
        server.start();
        try {
            var matches = client(server).query(List.of(new OsvClient.Package("Maven", "demo:lib", "1.0"),
                    new OsvClient.Package("Maven", "demo:other", "1.0")));
            assertEquals(1, matches.size());
            assertEquals(2, requests.get());
            assertEquals(1, details.get());
            var metadata = OsvClient.metadata(matches.getFirst());
            assertEquals("MODERATE", metadata.path("severity").asText());
            assertTrue(metadata.path("cvssScore").isNull());
            assertEquals("2.1", metadata.path("fixedVersions").get(0).asText());
            assertEquals("CVE-2026-12345", metadata.path("aliases").get(0).asText());
            assertEquals("2026-11-04T00:00:00Z", metadata.path("modified").asText());
            assertEquals(1, metadata.path("cvssVectors").size());
        } finally { server.stop(0); }
    }

    @Test void missingDetailsAndInvalidBatchCannotBecomeCleanResults() throws Exception {
        for (String batch : List.of("{\"results\":[]}", "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-missing\"}]}]}")) {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/querybatch", exchange -> send(exchange, 200, batch));
            server.createContext("/v1/vulns/", exchange -> send(exchange, 503, "{}"));
            server.start();
            try {
                assertThrows(IOException.class, () -> client(server).query(List.of(new OsvClient.Package("Maven", "demo:lib", "1.0"))));
            } finally { server.stop(0); }
        }
    }

    @Test void withdrawnAdvisoriesAreNotReported() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/querybatch", e -> send(e, 200, "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-new-2026\"}]}]}"));
        server.createContext("/v1/vulns/", e -> send(e, 200, RECORD.replace("\"summary\":", "\"withdrawn\":\"2026-11-04T00:00:00Z\",\"summary\":")));
        server.start();
        try { assertTrue(client(server).query(List.of(new OsvClient.Package("Maven", "demo:lib", "1.0"))).isEmpty()); }
        finally { server.stop(0); }
    }

    @Test void missingMetadataIsNotFilledWithInventedSeverityOrFixes() throws Exception {
        var record = JSON.readTree("{\"id\":\"GHSA-empty\",\"affected\":[]}");
        var metadata = OsvClient.metadata(new OsvClient.Match(new OsvClient.Package("Maven", "demo:lib", "1.0"), record));
        assertEquals("UNKNOWN", metadata.path("severity").asText());
        assertTrue(metadata.path("cvssScore").isNull());
        assertTrue(metadata.path("fixedVersions").isEmpty());
    }

    private static OsvClient client(HttpServer server) {
        return new OsvClient(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/"));
    }
    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
