package com.nullguard.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiOptionsTest {
    @TempDir Path root;
    private OpenApiOptions load(String json) throws Exception {
        return OpenApiOptions.load(Files.writeString(root.resolve("config.json"), json));
    }

    @Test void loadsExplicitServerAndSupportedAuthenticationDescriptions() throws Exception {
        var options = load("""
                {"title":"Demo", "version":"2", "tryItOut":true,
                 "servers":[{"url":"/api","description":"Local"},{"url":"https://example.com"}],
                 "securitySchemes":{"token":{"type":"apiKey","name":"X-Token","in":"header"},
                                    "basic":{"type":"http","scheme":"basic"}},
                 "security":[{"token":[]},{}]}
                """);
        assertTrue(options.tryItOut());
        assertEquals(Map.of("title", "Demo", "version", "2"), options.specFields().get("info"));
        assertEquals(2, ((List<?>)options.specFields().get("servers")).size());
        assertFalse(load("{}").tryItOut());
        assertEquals(OpenApiOptions.defaults(), OpenApiOptions.load(null));
        assertThrows(UnsupportedOperationException.class, () -> options.specFields().clear());
    }

    @Test void rejectsMalformedOrAmbiguousConfiguration() {
        var invalid = List.of("[]", "null", "{} {}", "{broken", "{\"title\":\" \"}", "{\"version\":1}",
                "{\"unknown\":true}", "{\"servers\":{}}", "{\"tryItOut\":true}", "{\"tryItOut\":\"true\"}",
                "{\"securitySchemes\":[]}", "{\"security\":{}}", "{\"security\":[1]}",
                "{\"security\":[{\"missing\":[]}]}",
                "{\"securitySchemes\":{\"bad name\":{\"type\":\"http\",\"scheme\":\"basic\"}}}",
                "{\"securitySchemes\":{\"a\":{\"type\":\"oauth2\"}}}",
                "{\"securitySchemes\":{\"a\":{\"type\":\"http\",\"scheme\":\"digest\"}}}",
                "{\"securitySchemes\":{\"a\":{\"type\":\"apiKey\",\"name\":\"Key\",\"in\":\"body\"}}}");
        for (String json : invalid) assertThrows(IllegalArgumentException.class, () -> load(json), json);
        assertThrows(IllegalArgumentException.class, () -> OpenApiOptions.load(root.resolve("missing")));
    }

    @Test void rejectsServerCredentialsQueriesFragmentsAndUnsupportedSchemes() {
        for (String url : List.of("https://user:password@example.com", "https://example.com?token=value",
                "https://example.com#fragment", "ftp://example.com", "//example.com", "relative", "http:/missing-host")) {
            assertThrows(IllegalArgumentException.class, () -> load("{\"servers\":[{\"url\":\"" + url + "\"}]}"), url);
        }
    }
}
