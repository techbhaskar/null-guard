package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiStaticCveIntegrationTest {

    @Test
    void generatesOpenApiSpecDtoSchemasStaticAnalysisFindingsAndReachableCves(@TempDir Path root) throws Exception {
        Path srcDir = root.resolve("src/main/java");
        Files.createDirectories(srcDir);

        // 1. Write pom.xml with a known vulnerable API-reachable dependency (log4j-core 2.14.1),
        //    a code-reachable dependency (snakeyaml 1.30), and an unreachable dependency (guava 31.1-jre)
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.logging.log4j</groupId>
                      <artifactId>log4j-core</artifactId>
                      <version>2.14.1</version>
                    </dependency>
                    <dependency>
                      <groupId>org.yaml</groupId>
                      <artifactId>snakeyaml</artifactId>
                      <version>1.30</version>
                    </dependency>
                    <dependency>
                      <groupId>com.google.guava</groupId>
                      <artifactId>guava</artifactId>
                      <version>31.1-jre</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        // Also write build.gradle and gradle.lockfile to exercise Gradle manifest parsers
        Files.writeString(root.resolve("build.gradle"), """
                dependencies {
                    implementation 'com.alibaba:fastjson:1.2.80'
                }
                """);
        Files.writeString(root.resolve("gradle.lockfile"), """
                # Gradle lockfile
                org.h2:h2:2.1.210=compileClasspath
                """);

        // Also write an external CVE report (--cve-input) in both OSV/custom and Trivy formats
        Path cveReport = root.resolve("cve-input.json");
        Files.writeString(cveReport, """
                {
                  "vulnerabilities": [
                    {
                      "id": "CVE-2026-9999",
                      "dependency": "demo.ext:custom-auth",
                      "version": "1.0.0",
                      "fixedVersion": "1.0.1",
                      "severity": "CRITICAL",
                      "cvssScore": 9.8,
                      "summary": "Custom auth token bypass",
                      "packagePrefixes": ["demo.ext"],
                      "symbols": ["AuthClient.verify"]
                    }
                  ],
                  "Results": [
                    {
                      "Vulnerabilities": [
                        {
                          "VulnerabilityID": "CVE-2026-8888",
                          "PkgName": "unused.lib:unused-core",
                          "InstalledVersion": "0.9.0",
                          "FixedVersion": "1.0.0",
                          "Severity": "MODERATE",
                          "Title": "Unused library issue"
                        }
                      ]
                    }
                  ]
                }
                """);

        // 2. Write Java source files exercising:
        //    - Class-level @RequestMapping("/api/v1/users") + @PostMapping, @GetMapping("/{id}"), @DeleteMapping("/{id}")
        //    - @PathVariable, @RequestParam, @RequestHeader, @RequestBody (without @Valid -> NG302)
        //    - Undocumented nullable return on @RestController (NG301)
        //    - Mutable state field in @RestController singleton (NG203)
        //    - Controller directly calling @Repository (NG204)
        //    - External/DB call inside a loop (NG201)
        //    - Swallowed empty catch block (NG202)
        //    - Cyclic call dependency between methods (NG205)
        //    - Unclosed FileInputStream outside try-with-resources (NG206)
        //    - Call to LogManager (CVE-2021-44228 on API chain -> NG401) and Yaml (CVE-2022-1471 internal -> NG402)
        Path demoDir = Files.createDirectories(srcDir.resolve("demo"));
        Files.writeString(demoDir.resolve("Annotations.java"), """
                package demo;
                @interface RestController {}
                @interface Service {}
                @interface Repository {}
                @interface RequestMapping { String value() default ""; String path() default ""; String method() default ""; }
                @interface GetMapping { String value() default ""; }
                @interface PostMapping { String value() default ""; }
                @interface PutMapping { String value() default ""; }
                @interface DeleteMapping { String value() default ""; }
                @interface PatchMapping { String value() default ""; }
                @interface PathVariable { String value() default ""; boolean required() default true; }
                @interface RequestParam { String value() default ""; boolean required() default true; String defaultValue() default ""; }
                @interface RequestHeader { String value() default ""; boolean required() default true; }
                @interface RequestBody { boolean required() default true; }
                @interface ResponseStatus { String value() default ""; }
                @interface NonNull {}
                @interface Nullable {}
                """);

        Files.writeString(demoDir.resolve("UserDtos.java"), """
                package demo;
                import java.util.List;
                import java.util.UUID;
                import java.time.LocalDate;
                import java.time.Instant;

                public class UserDtos {
                    public static class CreateUserRequest {
                        @NonNull private String username;
                        @Nullable private String bio;
                        private int age;
                        private long quota;
                        private double score;
                        private boolean active;
                        private UUID correlationId;
                        private LocalDate birthDate;
                        private Instant createdAt;
                        private List<String> roles;
                        private String[] tags;

                        public void setBio(String bio) { this.bio = bio; }
                    }

                    public static class UserResponse {
                        @NonNull private final String id = "u1";
                        private String username;
                    }
                }
                """);

        Files.writeString(demoDir.resolve("UserRepository.java"), """
                package demo;
                @Repository
                public class UserRepository {
                    public String findById(String id) {
                        return id;
                    }
                }
                """);

        Files.writeString(demoDir.resolve("UserService.java"), """
                package demo;
                import java.io.FileInputStream;
                import java.util.List;

                @Service
                public class UserService {
                    private final UserRepository userRepository = new UserRepository();

                    public String processUsers(List<String> ids) {
                        for (String id : ids) {
                            // NG201: DB repository call inside loop
                            userRepository.findById(id);
                        }
                        try {
                            // NG206: Unclosed FileInputStream outside try-with-resources
                            FileInputStream in = new FileInputStream("config.txt");
                            // Trigger API-reachable Log4j CVE (NG401)
                            String msg = "LogManager.getLogger";
                            LogManager.log(msg);
                        } catch (Exception e) {
                        }
                        return cycleA();
                    }

                    public String cycleA() { return cycleB(); }
                    public String cycleB() { return cycleA(); }

                    public void backgroundYamlLoad() {
                        // Internal-only call (not exposed on HTTP endpoint) -> NG402 CODE_REACHABLE for SnakeYAML
                        YamlHelper.parse("yaml.load");
                    }
                }

                class LogManager {
                    static void log(String s) {}
                }

                class YamlHelper {
                    static void parse(String s) {
                        Yaml y = new Yaml();
                        y.load(s);
                    }
                }

                class Yaml {
                    void load(String s) {}
                }
                """);

        Files.writeString(demoDir.resolve("UserController.java"), """
                package demo;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @RestController
                @RequestMapping("/api/v1/users")
                public class UserController {
                    // NG203: Stateful mutable field in singleton controller
                    private int requestCounter = 0;

                    private final UserService userService = new UserService();
                    private final UserRepository userRepository = new UserRepository();

                    @PostMapping
                    @ResponseStatus("CREATED")
                    public UserDtos.UserResponse createUser(
                            @RequestHeader("X-Tenant") String tenant,
                            @RequestBody UserDtos.CreateUserRequest body) {
                        this.requestCounter++;
                        // NG204: Controller directly calling Repository
                        userRepository.findById(tenant);
                        userService.processUsers(List.of(tenant));
                        // NG301: Undocumented nullable return on @RestController
                        return null;
                    }

                    @GetMapping("/{id}")
                    public Map<String, Object> getUser(
                            @PathVariable("id") String id,
                            @RequestParam(value = "filter", required = false) Optional<String> filter) {
                        return Map.of("id", userRepository.findById(id));
                    }

                    @DeleteMapping("/{id}")
                    public void deleteUser(@PathVariable("id") String id) {
                        userRepository.findById(id);
                    }

                    @PutMapping("/{id}")
                    public String updateUser(@PathVariable("id") String id, UserDtos.CreateUserRequest body) {
                        return userService.processUsers(List.of(id));
                    }

                    @PatchMapping("/{id}")
                    public List<UserDtos.UserResponse> patchUser(@PathVariable("id") String id) {
                        return List.of();
                    }
                }
                """);

        Path fakeJar = root.resolve("commons-text-1.9.jar");
        Files.writeString(fakeJar, "");

        NullGuardConfig config = NullGuardConfig.defaults()
                .outputDirectory(root.resolve("out"))
                .sourceRoots(List.of(srcDir))
                .classpathEntries(List.of(fakeJar))
                .cveInputFile(cveReport)
                .build();

        FinalAnalysisResult result = new EngineBootstrap(config).run(srcDir);

        // Verify all new rule IDs are emitted
        Set<String> ruleIds = result.getFindings().stream()
                .map(Finding::ruleId)
                .collect(Collectors.toSet());

        assertTrue(ruleIds.contains("NG201"), "Expected NG201 (loop DB call): " + ruleIds);
        assertTrue(ruleIds.contains("NG202"), "Expected NG202 (empty catch): " + ruleIds);
        assertTrue(ruleIds.contains("NG203"), "Expected NG203 (stateful singleton field): " + ruleIds);
        assertTrue(ruleIds.contains("NG204"), "Expected NG204 (controller->repository bypass): " + ruleIds);
        assertTrue(ruleIds.contains("NG205"), "Expected NG205 (cyclic call dependency): " + ruleIds);
        assertTrue(ruleIds.contains("NG206"), "Expected NG206 (unclosed resource): " + ruleIds);
        assertTrue(ruleIds.contains("NG301"), "Expected NG301 (undocumented nullable API return): " + ruleIds);
        assertTrue(ruleIds.contains("NG302"), "Expected NG302 (unvalidated @RequestBody): " + ruleIds);
        assertTrue(ruleIds.contains("NG401"), "Expected NG401 (API-reachable CVE): " + ruleIds);
        assertTrue(ruleIds.contains("NG402"), "Expected NG402 (Code-reachable CVE): " + ruleIds);
        assertTrue(ruleIds.contains("NG403"), "Expected NG403 (Unreachable CVE): " + ruleIds);

        // Verify OpenAPI 3.0 output and JSON report sections
        Path openApiOut = root.resolve("out/nullguard-openapi.json");
        Path jsonOut = root.resolve("out/nullguard-report-latest.json");
        Path htmlOut = root.resolve("out/nullguard-dashboard-latest.html");
        Files.createDirectories(root.resolve("out"));
        result.getVisualizations().writeOpenApiJson(openApiOut);
        result.getVisualizations().writeJson(jsonOut);
        HtmlDashboardRenderer.write(htmlOut, result, "20261016_160000", "demo-service");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode openApi = mapper.readTree(openApiOut.toFile());
        assertEquals("3.0.3", openApi.path("openapi").asText());
        assertTrue(openApi.path("paths").has("/api/v1/users"), "Composed basePath + empty method path");
        assertTrue(openApi.path("paths").has("/api/v1/users/{id}"), "Composed basePath + /{id}");
        assertTrue(openApi.path("components").path("schemas").has("CreateUserRequest"));
        assertTrue(openApi.path("components").path("schemas").has("UserResponse"));

        JsonNode fullReport = mapper.readTree(jsonOut.toFile());
        assertTrue(fullReport.has("openApiSpec"));
        assertTrue(fullReport.has("architectureSummary"));
        assertTrue(fullReport.has("cveSummary"));
        assertTrue(fullReport.path("cveSummary").path("apiReachableCount").asInt() >= 1);
        assertTrue(fullReport.path("cveSummary").path("codeReachableCount").asInt() >= 1);
        assertTrue(fullReport.path("cveSummary").path("unreachableCount").asInt() >= 1);

        String html = Files.readString(htmlOut);
        assertTrue(html.contains("Swagger / OpenAPI"));
        assertTrue(html.contains("Code Quality &amp; Arch"));
        assertTrue(html.contains("Reachable CVEs"));
    }
}
