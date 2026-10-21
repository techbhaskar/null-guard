package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiCorrectnessTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode spec(FinalAnalysisResult result) { return mapper.valueToTree(result.getVisualizations().getOpenApiSpec()); }
    private void source(String name, String content) throws Exception { Files.writeString(root.resolve(name), content); }

    @Test void expandsAllRoutesAndVerbsWithUniqueStableIdsAndNoInventedErrors() throws Exception {
        source("Controller.java", """
            package demo;
            @RequestMapping({"/v1", "/v2"}) class Controller {
              @RequestMapping(path={"/users", "/people"}, method={RequestMethod.GET, RequestMethod.POST})
              String list(@RequestParam(defaultValue="all") String filter) { return "ok"; }
              @GetMapping("/one") String find(String id) { return id; }
              @GetMapping("/two") String find(int id) { return "ok"; }
              @RequestMapping("/any") String any() { return "ok"; }
              @HEAD @Path("/head") String header() { return "ok"; }
            }
            """);
        var config = NullGuardConfig.defaults().build();
        var result = new EngineBootstrap(config).run(root);
        JsonNode document = spec(result);
        Set<String> ids = new HashSet<>();
        document.path("paths").forEach(path -> path.forEach(op -> {
            assertTrue(ids.add(op.path("operationId").asText()), "Operation ID collision");
            assertFalse(op.path("responses").has("400"));
            assertFalse(op.path("responses").has("500"));
        }));
        assertEquals(30, ids.size());
        assertEquals(2, document.at("/paths/~1v1~1users").size());
        assertEquals(8, document.at("/paths/~1v2~1any").size());
        assertTrue(document.at("/paths/~1v1~1head").has("head"));
        assertEquals(document, spec(new EngineBootstrap(config).run(root)));
        save("variants", document);
    }

    @Test void preservesQualifiedDtoIdentityAndNullableReferencesAndWritesOfflineUi() throws Exception {
        source("A.java", "package a; class User { String name; }");
        source("B.java", "package b; class User { int age; }");
        source("Controller.java", """
            package demo;
            class Controller {
              @GetMapping("/a") a.User getA() { return null; }
              @GetMapping("/b") b.User getB() { return null; }
              @GetMapping("/ambiguous") User ambiguous() { return null; }
            }
            """);
        var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(root);
        JsonNode document = spec(result);
        assertTrue(document.at("/components/schemas").has("a.User"));
        assertTrue(document.at("/components/schemas").has("b.User"));
        assertFalse(document.at("/components/schemas").has("User"));
        var response = document.at("/paths/~1a/get/responses/200/content/application~1json/schema");
        assertEquals("#/components/schemas/a.User", response.at("/anyOf/0/$ref").asText());
        assertTrue(response.at("/anyOf/1/nullable").asBoolean());
        assertTrue(response.at("/anyOf/1/enum/0").isNull());
        assertEquals("User", document.at("/paths/~1ambiguous/get/responses/200/content/application~1json/schema/x-java-type").asText());
        Path output = Files.createDirectories(root.resolve("out"));
        HtmlDashboardRenderer.write(output.resolve("dashboard.html"), result, "test", "demo");
        String ui = Files.readString(output.resolve("nullguard-swagger.html"));
        assertTrue(ui.contains("SwaggerUIBundle"));
        assertTrue(ui.contains("validatorUrl:null"));
        assertTrue(ui.contains("persistAuthorization:false"));
        assertFalse(document.path("x-nullguard-try-it-out").asBoolean());
        assertTrue(Files.size(output.resolve("swagger-ui/swagger-ui-bundle.js")) > 100_000);
        assertTrue(Files.exists(output.resolve("swagger-ui/LICENSE")));
        save("schemas", document);
    }

    @Test void rejectsConflictingOperationsInsteadOfSilentlyOverwriting() throws Exception {
        source("Controller.java", "class Controller { @GetMapping(\"/same\") String one(){return \"a\";} @GetMapping(\"/same\") String two(){return \"b\";} }");
        assertThrows(IllegalArgumentException.class, () -> new EngineBootstrap(NullGuardConfig.defaults().build()).run(root));
    }

    @Test void explicitServersAndAuthAreAppliedWithoutSecretsAndUnsafeOptionsFailFast() throws Exception {
        Path config = root.resolve("openapi.json");
        Files.writeString(config, """
            {"title":"Service API","version":"2.0","servers":[{"url":"https://api.example.test/v1"}],
             "securitySchemes":{"bearerAuth":{"type":"http","scheme":"bearer","bearerFormat":"JWT"},
               "basicAuth":{"type":"http","scheme":"basic"},"key":{"type":"apiKey","in":"header","name":"X-API-Key"}},
             "security":[{"bearerAuth":[]}],"tryItOut":true}
            """);
        source("Controller.java", "class Controller { @GetMapping(\"/hello\") String hello(){return \"ok\";} }");
        JsonNode document = spec(new EngineBootstrap(NullGuardConfig.defaults().openApiConfigFile(config).build()).run(root));
        assertEquals("Service API", document.at("/info/title").asText());
        assertEquals("2.0", document.at("/info/version").asText());
        assertEquals("bearer", document.at("/components/securitySchemes/bearerAuth/scheme").asText());
        assertTrue(document.path("x-nullguard-try-it-out").asBoolean());
        save("configured", document);
        for (String invalid : List.of("{\"tryItOut\":true}", "{\"token\":\"secret\"}", "{\"security\":[{\"missing\":[]}]}",
                "{\"servers\":[{\"url\":\"javascript:alert(1)\"}]}", "{\"servers\":[{\"url\":\"https://user:secret@example.test\"}]}",
                "{\"tryItOut\":\"yes\"}", "{\"securitySchemes\":{\"auth\":{\"type\":\"http\",\"scheme\":\"bearer\",\"token\":\"secret\"}}}")) {
            Files.writeString(config, invalid);
            assertThrows(IllegalArgumentException.class, () -> NullGuardConfig.defaults().openApiConfigFile(config).build(), invalid);
        }
    }

    @Test void preservesDeclaredErrorAndNoContentResponsesAndNestedContainers() throws Exception {
        source("Controller.java", """
            class User { String name; }
            class Controller {
              @GetMapping("/missing") @ResponseStatus(value=HttpStatus.NOT_FOUND, reason="CREATED") String missing() { return "missing"; }
              @DeleteMapping("/done") @ResponseStatus(HttpStatus.NO_CONTENT) String done() { return "ignored"; }
              @GetMapping("/nested") ResponseEntity<Mono<Map<String, List<User>>>> nested() { return null; }
            }
            """);
        var document = spec(new EngineBootstrap(NullGuardConfig.defaults().build()).run(root));
        assertTrue(document.at("/paths/~1missing/get/responses").has("404"));
        assertFalse(document.at("/paths/~1done/delete/responses/204").has("content"));
        assertEquals("array", document.at("/paths/~1nested/get/responses/200/content/application~1json/schema/additionalProperties/type").asText());
        save("responses", document);
    }

    @Test void jaxRsHeadWithoutMethodPathUsesClassPath() throws Exception {
        source("Resource.java", "@Path(\"/health\") class Resource { @HEAD String head(){ return \"ok\"; } }");
        var document = spec(new EngineBootstrap(NullGuardConfig.defaults().build()).run(root));
        assertTrue(document.at("/paths/~1health").has("head"));
        assertFalse(document.at("/paths/~1health/head/responses/200").has("content"));
    }

    private void save(String name, JsonNode document) throws Exception {
        Path directory = Files.createDirectories(Path.of("target/openapi-validation"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), document);
    }
}
