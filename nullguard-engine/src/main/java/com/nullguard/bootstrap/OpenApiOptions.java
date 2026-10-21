package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** Explicit documentation configuration. Never accepts credentials or authorization values. */
public record OpenApiOptions(Map<String, Object> specFields, boolean tryItOut) {
    public OpenApiOptions { specFields = Map.copyOf(specFields); }
    public static OpenApiOptions defaults() { return new OpenApiOptions(Map.of(), false); }

    public static OpenApiOptions load(Path file) {
        if (file == null) return defaults();
        try {
            ObjectMapper mapper = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root = mapper.readTree(file.toFile());
            allowed(root, "title", "version", "servers", "securitySchemes", "security", "tryItOut");
            Map<String, Object> fields = new LinkedHashMap<>();
            Map<String, Object> info = new LinkedHashMap<>();
            for (String key : List.of("title", "version")) if (root.has(key)) info.put(key, text(root.get(key)));
            if (!info.isEmpty()) fields.put("info", info);
            List<Map<String, Object>> servers = new ArrayList<>();
            if (root.has("servers")) {
                if (!root.get("servers").isArray()) throw new IllegalArgumentException("servers must be an array");
                for (JsonNode server : root.get("servers")) {
                    allowed(server, "url", "description");
                    String url = text(server.path("url"));
                    java.net.URI uri = java.net.URI.create(url);
                    if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                            || !(Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) && uri.getHost() != null
                            || uri.getScheme() == null && url.startsWith("/") && !url.startsWith("//")))
                        throw new IllegalArgumentException("Server must be an HTTP(S) URL or root-relative path without credentials/query/fragment");
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("url", url);
                    if (server.has("description")) value.put("description", text(server.get("description")));
                    servers.add(value);
                }
                fields.put("servers", servers);
            }
            Map<String, Object> schemes = new LinkedHashMap<>();
            if (root.has("securitySchemes")) {
                JsonNode definitions = root.get("securitySchemes");
                if (!definitions.isObject()) throw new IllegalArgumentException("securitySchemes must be an object");
                definitions.fields().forEachRemaining(entry -> {
                    if (!entry.getKey().matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid security scheme name");
                    JsonNode scheme = entry.getValue();
                    String type = text(scheme.path("type"));
                    if (type.equals("http")) {
                        allowed(scheme, "type", "scheme", "bearerFormat", "description");
                        if (!Set.of("bearer", "basic").contains(text(scheme.path("scheme")))) throw new IllegalArgumentException("Only bearer/basic HTTP schemes are supported");
                    } else if (type.equals("apiKey")) {
                        allowed(scheme, "type", "name", "in", "description");
                        text(scheme.path("name"));
                        if (!Set.of("header", "query", "cookie").contains(text(scheme.path("in")))) throw new IllegalArgumentException("Invalid API key location");
                    } else throw new IllegalArgumentException("Supported security types: http, apiKey");
                    scheme.fields().forEachRemaining(f -> text(f.getValue()));
                    schemes.put(entry.getKey(), mapper.convertValue(scheme, Map.class));
                });
            }
            fields.put("securitySchemes", schemes);
            if (root.has("security")) {
                if (!root.get("security").isArray()) throw new IllegalArgumentException("security must be an array");
                for (JsonNode requirement : root.get("security")) {
                    if (!requirement.isObject()) throw new IllegalArgumentException("Invalid security requirement");
                    requirement.fields().forEachRemaining(f -> {
                        if (!schemes.containsKey(f.getKey()) || !f.getValue().isArray() || !f.getValue().isEmpty())
                            throw new IllegalArgumentException("Security requirements must reference configured schemes with empty scopes");
                    });
                }
                fields.put("security", mapper.convertValue(root.get("security"), List.class));
            }
            if (root.has("tryItOut") && !root.get("tryItOut").isBoolean()) throw new IllegalArgumentException("tryItOut must be boolean");
            boolean enabled = root.path("tryItOut").asBoolean(false);
            if (enabled && servers.isEmpty()) throw new IllegalArgumentException("tryItOut requires an explicit server");
            return new OpenApiOptions(fields, enabled);
        } catch (java.io.IOException failure) { throw new IllegalArgumentException("Cannot read OpenAPI configuration: " + file, failure); }
    }
    private static String text(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Expected non-empty string in OpenAPI configuration");
        return value.asText();
    }
    private static void allowed(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected configuration object");
        Set<String> allowed = Set.of(keys);
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported configuration field: " + key); });
    }
}
