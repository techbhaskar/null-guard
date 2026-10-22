package com.nullguard.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AstMetadataExtractorTest {
    @Test void fieldMetadataDistinguishesInjectionNullabilityAndMutation() {
        var owner = StaticJavaParser.parse("""
                @Data class Dto {
                    @Autowired Object dependency;
                    @Nullable Object optional;
                    @NotNull final Object stable = new Object();
                    static int counter;
                    Object left, right;
                    void update(Object value) { this.left = value; right = value; }
                }
                """).getClassByName("Dto").orElseThrow();
        var fields = AstMetadataExtractor.extractFields(owner).stream()
                .collect(java.util.stream.Collectors.toMap(com.nullguard.core.model.FieldModel::name, f -> f));
        assertTrue(fields.get("dependency").injected());
        assertTrue(fields.get("dependency").mutated());
        assertTrue(fields.get("optional").nullable());
        assertTrue(fields.get("stable").nonNull());
        assertFalse(fields.get("stable").mutated());
        assertTrue(fields.get("counter").primitive());
        assertTrue(fields.get("counter").staticField());
        assertTrue(fields.get("left").mutated());
        assertTrue(fields.get("right").mutated());
    }

    @Test void parameterLocationsRespectOptionalDefaultsAndValidation() {
        for (String annotation : List.of("@PathVariable(\"id\")", "@PathParam(\"id\")")) {
            var p = parameter(annotation, "String", false);
            assertEquals("path", p.inLocation());
            assertEquals("id", p.paramKey());
            assertTrue(p.required());
        }
        assertFalse(parameter("@PathVariable(required=false)", "String", false).required());
        for (String annotation : List.of("@RequestParam", "@QueryParam")) {
            assertEquals("query", parameter(annotation, "String", false).inLocation());
            assertTrue(parameter(annotation, "String", false).required());
            assertFalse(parameter(annotation, "Optional<String>", false).required());
            assertFalse(parameter(annotation, "String", true).required());
        }
        assertEquals("queryName", parameter("@RequestParam(name=\"queryName\", defaultValue=\"all\")", "String", false).paramKey());
        assertFalse(parameter("@RequestParam(defaultValue=\"all\")", "String", false).required());
        assertFalse(parameter("@RequestParam(required=false)", "String", false).required());
        for (String annotation : List.of("@RequestHeader(\"X-Token\")", "@HeaderParam(\"X-Token\")")) {
            var p = parameter(annotation, "String", false);
            assertEquals("header", p.inLocation());
            assertEquals("X-Token", p.paramKey());
            assertTrue(p.required());
        }
        assertFalse(parameter("@RequestHeader(required=false)", "String", false).required());
        assertFalse(parameter("@RequestHeader(defaultValue=\"x\")", "String", false).required());
        assertFalse(parameter("@RequestHeader", "String", true).required());
        assertEquals("body", parameter("@RequestBody @Valid", "String", false).inLocation());
        assertTrue(parameter("@RequestBody @Valid", "String", false).validated());
        assertFalse(parameter("@RequestBody(required=false)", "String", false).required());
        assertFalse(parameter("@RequestBody", "String", true).required());
        assertTrue(parameter("@Deprecated", "int", false).primitive());
    }

    private static com.nullguard.core.model.ParameterModel parameter(String annotation, String type, boolean nullable) {
        var m = StaticJavaParser.parseMethodDeclaration("void endpoint(" + annotation + " " + type + " value) {}");
        return AstMetadataExtractor.buildParameterModel(m, m.getParameter(0), false, nullable);
    }

    private static MethodDeclaration method(String annotation) {
        return StaticJavaParser.parseMethodDeclaration(annotation + " public void endpoint() {}");
    }

    @Test void mappingVariantsAreNormalizedAndDynamicPathsRejected() {
        assertEquals(List.of("/one", "/two"), AstMetadataExtractor.extractPaths(method("@GetMapping(path={\"one\", \"/two\", \"one\"})")));
        assertEquals(List.of(""), AstMetadataExtractor.extractPaths(method("@GetMapping")));
        assertEquals(List.of(""), AstMetadataExtractor.extractPaths(method("@Deprecated")));
        assertThrows(IllegalArgumentException.class, () -> AstMetadataExtractor.extractPaths(method("@GetMapping(BASE)")));
        var owner = StaticJavaParser.parse("@Path(\"api\") class Api {}").getClassByName("Api").orElseThrow();
        assertEquals("/api", AstMetadataExtractor.extractBasePath(owner));
        assertEquals(Set.of("Path"), AstMetadataExtractor.extractAnnotations(owner));
        assertEquals("", AstMetadataExtractor.extractBasePath(StaticJavaParser.parse("class Empty {}").getClassByName("Empty").orElseThrow()));
    }

    @Test void verbsAndResponseStatusesHonorExplicitAnnotations() {
        Map.of("GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE",
                "PatchMapping", "PATCH", "HEAD", "HEAD", "OPTIONS", "OPTIONS").forEach((ann, verb) -> {
            assertEquals(verb, AstMetadataExtractor.extractHttpMethod(method("@" + ann)));
            assertEquals(List.of(verb), AstMetadataExtractor.extractHttpMethods(method("@" + ann)));
        });
        for (String verb : List.of("GET", "POST", "PUT", "DELETE", "PATCH")) {
            var m = method("@RequestMapping(method=RequestMethod." + verb + ")");
            assertEquals(verb, AstMetadataExtractor.extractHttpMethod(m));
            assertEquals(List.of(verb), AstMetadataExtractor.extractHttpMethods(m));
        }
        assertEquals(8, AstMetadataExtractor.extractHttpMethods(method("@RequestMapping")).size());
        assertEquals(List.of("GET", "POST"), AstMetadataExtractor.extractHttpMethods(method("@RequestMapping(method={RequestMethod.GET, RequestMethod.POST})")));
        assertEquals(List.of(), AstMetadataExtractor.extractHttpMethods(method("@Deprecated")));
        assertEquals(202, AstMetadataExtractor.extractResponseStatus(method("@ResponseStatus(code=HttpStatus.ACCEPTED)"), "POST"));
        assertEquals(204, AstMetadataExtractor.extractResponseStatus(method("@ResponseStatus(\"204\")"), "GET"));
        assertEquals(500, AstMetadataExtractor.extractResponseStatus(method("@ResponseStatus"), "GET"));
        assertEquals(200, AstMetadataExtractor.extractResponseStatus(method("@Deprecated"), "POST"));
        assertThrows(IllegalArgumentException.class, () -> AstMetadataExtractor.extractResponseStatus(method("@ResponseStatus(UNKNOWN)"), "GET"));
        assertEquals("/one", AstMetadataExtractor.extractHttpPath(method("@GetMapping(value=\"one\")")));
        assertEquals("", AstMetadataExtractor.extractHttpPath(method("@GetMapping")));
        assertEquals("", AstMetadataExtractor.extractHttpPath(method("@Deprecated")));
    }

    @Test void findingsDistinguishIgnoredExceptionsAndClosedResources() {
        var m = StaticJavaParser.parseMethodDeclaration("""
                void process() throws Exception {
                    while (ready()) { repository.findAll(); }
                    do { client.fetch(); } while (ready());
                    for (int i=0; i<2; i++) { repository.findAll(); }
                    for (String id : ids) { repository.findById(id); }
                    try { work(); } catch (Exception ignored) {}
                    try { work(); } catch (Exception failure) {}
                    try { work(); } catch (Exception failure) { log(failure); }
                    InputStream closed = open(); closed.close();
                    try (InputStream safe = open()) { read(safe); }
                    InputStream leaked = open();
                    InputStream uninitialized;
                }
                """);
        var findings = AstMetadataExtractor.extractMethodFindings(m);
        assertEquals(4, findings.stream().filter(f -> f.ruleId().equals("NG201")).count());
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("NG202")).count());
        assertEquals(1, findings.stream().filter(f -> f.ruleId().equals("NG206")).count());
        assertTrue(AstMetadataExtractor.extractMethodFindings(StaticJavaParser.parseMethodDeclaration("abstract void process();")).isEmpty());
    }
}
