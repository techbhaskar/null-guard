package com.nullguard.bootstrap;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Generates an offline index of the timestamped reports in a single output directory. */
public final class RunHistory {
    private static final Pattern ARCHIVE = Pattern.compile(
            "nullguard-(report|dashboard)-(\\d{8}_\\d{6}(?:_\\d+)?)[.](json|html)");
    private static final ObjectMapper JSON = new ObjectMapper();

    private RunHistory() {}

    private record Run(String id, String dashboard, String report, JsonNode findings) {}

    public static void write(Path directory) throws IOException {
        var runs = new TreeMap<String, Run>(Comparator.reverseOrder());
        try (var files = Files.newDirectoryStream(directory, "nullguard-*")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                var match = ARCHIVE.matcher(name);
                if (!match.matches() || !Files.isRegularFile(file)) continue;
                String id = match.group(2);
                Run run = runs.getOrDefault(id, new Run(id, null, null, null));
                if (match.group(1).equals("report") && match.group(3).equals("json")) {
                    runs.put(id, new Run(id, run.dashboard(), name, readFindings(file)));
                } else if (match.group(1).equals("dashboard") && match.group(3).equals("html")) {
                    runs.put(id, new Run(id, name, run.report(), run.findings()));
                }
            }
        }

        String template;
        try (var resource = RunHistory.class.getResourceAsStream("/nullguard-history.html")) {
            if (resource == null) throw new IOException("Run History template is missing");
            template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        String data = JSON.writeValueAsString(runs.values()).replace("<", "\\u003c");
        Files.writeString(directory.resolve("nullguard-history.html"), template.replace("{{RUN_DATA}}", data));
    }

    private static JsonNode readFindings(Path file) {
        try (var parser = JSON.createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return null;
            JsonNode findings = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if ("findings".equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
                    findings = JSON.readTree(parser);
                } else {
                    parser.skipChildren();
                }
            }
            return parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null ? findings : null;
        } catch (IOException unreadable) {
            return null;
        }
    }
}
