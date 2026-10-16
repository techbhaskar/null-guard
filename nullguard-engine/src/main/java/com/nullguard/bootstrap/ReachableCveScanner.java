package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.SemanticCallSite;
import com.nullguard.core.model.SourceLocation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3: Reachable CVE Scanner (NG401–NG403).
 *
 * <p>Discovers project dependencies from Maven/Gradle manifests and classpath JARs,
 * matches advisories from offline reports (--cve-input), built-in Maven CVE signatures,
 * and optional OSV.dev batch API queries, and classifies each vulnerability using
 * NullGuard's GlobalCallGraph and ApiEndpointModels into:
 * <ul>
 *   <li>{@code API_REACHABLE} (NG401) — invoked along a public HTTP endpoint call chain</li>
 *   <li>{@code CODE_REACHABLE} (NG402) — invoked by internal code, not exposed via HTTP</li>
 *   <li>{@code UNREACHABLE} (NG403) — present in dependencies, never called in code</li>
 * </ul>
 */
public final class ReachableCveScanner {

    public record DependencyCoordinate(String groupId, String artifactId, String version) {
        public String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    public record AdvisoryEntry(
            String cveId,
            String groupId,
            String artifactId,
            String maxVulnerableVersion,
            String fixedVersion,
            String severity,
            double cvssScore,
            String summary,
            List<String> packagePrefixes,
            List<String> classOrMethodTokens
    ) {}

    public record ScanOutcome(List<Finding> findings, Map<String, Object> cveSummary) {}

    private static final List<AdvisoryEntry> BUILTIN_CATALOG = List.of(
            new AdvisoryEntry(
                    "CVE-2021-44228", "org.apache.logging.log4j", "log4j-core", "2.14.1", "2.17.1",
                    "CRITICAL", 10.0, "Apache Log4j2 JNDI RCE (Log4Shell) in message lookup substitution",
                    List.of("org.apache.logging.log4j"),
                    List.of("LogManager", "Logger.", "JndiLookup", "ThreadContext")
            ),
            new AdvisoryEntry(
                    "CVE-2022-22965", "org.springframework", "spring-beans", "5.3.17", "5.3.18",
                    "CRITICAL", 9.8, "Spring Framework RCE via DataBinder ClassLoader manipulation (Spring4Shell)",
                    List.of("org.springframework.beans", "org.springframework.web.bind"),
                    List.of("DataBinder", "WebDataBinder", "BeanWrapperImpl")
            ),
            new AdvisoryEntry(
                    "CVE-2022-42003", "com.fasterxml.jackson.core", "jackson-databind", "2.13.4", "2.13.4.2",
                    "HIGH", 7.5, "Jackson Databind resource exhaustion via deeply nested wrapper array deserialization",
                    List.of("com.fasterxml.jackson.databind"),
                    List.of("ObjectMapper", "readValue", "readTree", "ObjectReader")
            ),
            new AdvisoryEntry(
                    "CVE-2020-36518", "com.fasterxml.jackson.core", "jackson-databind", "2.13.2", "2.13.2.1",
                    "HIGH", 7.5, "Jackson Databind stack overflow via deeply nested JSON objects",
                    List.of("com.fasterxml.jackson.databind"),
                    List.of("ObjectMapper", "readValue", "readTree", "ObjectReader")
            ),
            new AdvisoryEntry(
                    "CVE-2022-1471", "org.yaml", "snakeyaml", "1.33", "2.0",
                    "CRITICAL", 9.8, "SnakeYAML Constructor arbitrary code execution during YAML deserialization",
                    List.of("org.yaml.snakeyaml"),
                    List.of("Yaml", "yaml.load", "SafeConstructor")
            ),
            new AdvisoryEntry(
                    "CVE-2022-42889", "org.apache.commons", "commons-text", "1.9", "1.10.0",
                    "CRITICAL", 9.8, "Apache Commons Text RCE via StringSubstitutor interpolation (Text4Shell)",
                    List.of("org.apache.commons.text"),
                    List.of("StringSubstitutor", "StringLookupFactory", "replace(")
            ),
            new AdvisoryEntry(
                    "CVE-2015-7501", "commons-collections", "commons-collections", "3.2.1", "3.2.2",
                    "CRITICAL", 9.8, "Apache Commons Collections arbitrary remote code execution via InvokerTransformer",
                    List.of("org.apache.commons.collections"),
                    List.of("InvokerTransformer", "ChainedTransformer", "LazyMap")
            ),
            new AdvisoryEntry(
                    "CVE-2022-25845", "com.alibaba", "fastjson", "1.2.80", "1.2.83",
                    "CRITICAL", 9.8, "Fastjson AutoType bypass allowing remote code execution during JSON parsing",
                    List.of("com.alibaba.fastjson"),
                    List.of("JSON.parse", "JSON.parseObject", "ParserConfig")
            ),
            new AdvisoryEntry(
                    "CVE-2022-23221", "com.h2database", "h2", "2.1.210", "2.1.212",
                    "CRITICAL", 9.8, "H2 Database Console & JDBC URL remote code execution",
                    List.of("org.h2"),
                    List.of("DriverManager.getConnection", "JdbcDataSource", "Server.createWebServer")
            ),
            new AdvisoryEntry(
                    "CVE-2022-21724", "org.postgresql", "postgresql", "42.3.2", "42.3.3",
                    "CRITICAL", 9.8, "PgJDBC arbitrary class instantiation via loggerLevel/socketFactory JDBC properties",
                    List.of("org.postgresql"),
                    List.of("PGSimpleDataSource", "DriverManager.getConnection", "PgConnection")
            ),
            new AdvisoryEntry(
                    "CVE-2023-2976", "com.google.guava", "guava", "31.1-jre", "32.0.0-jre",
                    "MODERATE", 7.1, "Google Guava insecure temporary directory creation in Files.createTempDir",
                    List.of("com.google.common.io"),
                    List.of("Files.createTempDir", "FileBackedOutputStream")
            )
    );

    private static final Pattern POM_DEP_PATTERN = Pattern.compile(
            "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>(?:\\s*<version>([^<$]+)</version>)?[\\s\\S]*?</dependency>");
    private static final Pattern GRADLE_DEP_PATTERN = Pattern.compile(
            "(?:implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*['\"]([^:'\"]+):([^:'\"]+):([^'\"]+)['\"]\\s*\\)?");
    private static final Pattern JAR_NAME_PATTERN = Pattern.compile("^([a-zA-Z0-9._-]+?)-(\\d+\\.[0-9A-Za-z._-]+)\\.jar$");

    private ReachableCveScanner() {}

    public static ScanOutcome scan(
            Path projectRoot,
            NullGuardConfig config,
            ProjectModel project,
            GlobalCallGraph callGraph,
            List<ApiEndpointModel> endpoints) {

        List<DependencyCoordinate> dependencies = discoverDependencies(projectRoot, config);
        List<MatchedVulnerability> matched = new ArrayList<>();

        // 1. Match against built-in Maven CVE catalog
        for (DependencyCoordinate dep : dependencies) {
            for (AdvisoryEntry adv : BUILTIN_CATALOG) {
                if (matchesCoordinate(dep, adv.groupId(), adv.artifactId())
                        && compareVersions(dep.version(), adv.maxVulnerableVersion()) <= 0) {
                    matched.add(new MatchedVulnerability(
                            adv.cveId(),
                            dep.groupId() + ":" + dep.artifactId(),
                            dep.version(),
                            adv.fixedVersion(),
                            adv.severity(),
                            adv.cvssScore(),
                            adv.summary(),
                            adv.packagePrefixes(),
                            adv.classOrMethodTokens()
                    ));
                }
            }
        }

        // 2. Parse external --cve-input JSON report if configured
        if (config.getCveInputFile() != null && Files.isRegularFile(config.getCveInputFile())) {
            matched.addAll(parseExternalCveReport(config.getCveInputFile()));
        }

        // 3. Optional online OSV.dev batch lookup if enabled
        if (config.isOsvOnlineLookup() && !dependencies.isEmpty()) {
            matched.addAll(queryOsvBatchApi(dependencies));
        }

        // Deduplicate by CVE ID + dependency
        Map<String, MatchedVulnerability> uniqueVulns = new LinkedHashMap<>();
        for (MatchedVulnerability v : matched) {
            uniqueVulns.putIfAbsent(v.cveId() + "@" + v.dependency(), v);
        }

        // Build method index and fallback location for project-level findings
        Map<String, MethodModel> methodById = new LinkedHashMap<>();
        SourceLocation fallbackLocation = null;
        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel m : cls.getMethods().values()) {
                        String id = MethodIds.of(pkg, cls, m);
                        methodById.put(id, m);
                        if (fallbackLocation == null && m.getSourceLocation().isPresent() && m.getSourceLocation().get().startLine() > 0) {
                            fallbackLocation = m.getSourceLocation().get();
                        }
                    }
                }
            }
        }

        List<Finding> cveFindings = new ArrayList<>();
        List<Map<String, Object>> vulnRecords = new ArrayList<>();
        int apiReachableCount = 0;
        int codeReachableCount = 0;
        int unreachableCount = 0;

        for (MatchedVulnerability vuln : uniqueVulns.values()) {
            Set<String> directCallerMethods = findDirectCallers(vuln, methodById, callGraph);
            List<String> affectedEndpoints = new ArrayList<>();
            for (ApiEndpointModel ep : endpoints) {
                boolean hitsChain = ep.getPropagationChain().stream().anyMatch(node ->
                        directCallerMethods.contains(node) || matchesExternalNode(node, vuln));
                if (hitsChain) {
                    affectedEndpoints.add(ep.getHttpMethod() + " " + ep.getPath());
                }
            }

            String reachability;
            String ruleId;
            String findingSeverity;
            if (!affectedEndpoints.isEmpty()) {
                reachability = "API_REACHABLE";
                ruleId = "NG401";
                findingSeverity = "error";
                apiReachableCount++;
            } else if (!directCallerMethods.isEmpty()) {
                reachability = "CODE_REACHABLE";
                ruleId = "NG402";
                findingSeverity = "warning";
                codeReachableCount++;
            } else {
                reachability = "UNREACHABLE";
                ruleId = "NG403";
                findingSeverity = "note";
                unreachableCount++;
            }

            String primaryMethodId = !directCallerMethods.isEmpty()
                    ? directCallerMethods.iterator().next()
                    : (!methodById.isEmpty() ? methodById.keySet().iterator().next() : "project#dependencies()");
            MethodModel primaryMethod = methodById.get(primaryMethodId);
            SourceLocation findingLoc = primaryMethod != null && primaryMethod.getSourceLocation().isPresent()
                    ? primaryMethod.getSourceLocation().get()
                    : fallbackLocation;

            String message = String.format(
                    Locale.ROOT,
                    "[%s] %s in %s:%s (fix: %s) — %s%s",
                    reachability,
                    vuln.cveId(),
                    vuln.dependency(),
                    vuln.version(),
                    vuln.fixedVersion(),
                    vuln.summary(),
                    affectedEndpoints.isEmpty() ? "" : " [Exposed on: " + String.join(", ", affectedEndpoints) + "]"
            );

            if (findingLoc != null && findingLoc.startLine() > 0) {
                cveFindings.add(new Finding(ruleId, findingSeverity, primaryMethodId, message, findingLoc));
            }

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("cveId", vuln.cveId());
            rec.put("dependency", vuln.dependency());
            rec.put("version", vuln.version());
            rec.put("fixedVersion", vuln.fixedVersion());
            rec.put("severity", vuln.severity());
            rec.put("cvssScore", vuln.cvssScore());
            rec.put("reachability", reachability);
            rec.put("ruleId", ruleId);
            rec.put("summary", vuln.summary());
            rec.put("affectedEndpoints", affectedEndpoints);
            rec.put("callerMethods", List.copyOf(directCallerMethods));
            vulnRecords.add(rec);
        }

        double penalty = (apiReachableCount * 25.0) + (codeReachableCount * 12.0) + (unreachableCount * 2.0);
        double securityScore = Math.max(0.0, Math.round((100.0 - penalty) * 100.0) / 100.0);
        String securityGrade = securityScore >= 90.0 ? "A"
                : securityScore >= 75.0 ? "B"
                : securityScore >= 60.0 ? "C"
                : securityScore >= 40.0 ? "D" : "F";

        Map<String, Object> cveSummary = new LinkedHashMap<>();
        cveSummary.put("securityScore", securityScore);
        cveSummary.put("securityGrade", securityGrade);
        cveSummary.put("totalDependencies", dependencies.size());
        cveSummary.put("totalVulnerabilities", vulnRecords.size());
        cveSummary.put("apiReachableCount", apiReachableCount);
        cveSummary.put("codeReachableCount", codeReachableCount);
        cveSummary.put("unreachableCount", unreachableCount);
        cveSummary.put("vulnerabilities", vulnRecords);

        List<Finding> sortedFindings = cveFindings.stream()
                .distinct()
                .sorted(Comparator.comparing(Finding::methodId)
                        .thenComparing(Finding::ruleId)
                        .thenComparing(Finding::message))
                .toList();

        return new ScanOutcome(sortedFindings, cveSummary);
    }

    static List<DependencyCoordinate> discoverDependencies(Path projectRoot, NullGuardConfig config) {
        Map<String, DependencyCoordinate> coords = new LinkedHashMap<>();
        List<Path> candidateDirs = new ArrayList<>();
        if (projectRoot != null) {
            Path cur = projectRoot.toAbsolutePath().normalize();
            for (int depth = 0; depth < 5 && cur != null; depth++) {
                candidateDirs.add(cur);
                cur = cur.getParent();
            }
        }

        for (Path dir : candidateDirs) {
            parsePomDependencies(dir.resolve("pom.xml"), coords);
            parseGradleDependencies(dir.resolve("build.gradle"), coords);
            parseGradleDependencies(dir.resolve("build.gradle.kts"), coords);
            parseGradleLockfile(dir.resolve("gradle.lockfile"), coords);
        }

        if (config != null && config.getClasspathEntries() != null) {
            for (Path cp : config.getClasspathEntries()) {
                if (cp == null || cp.getFileName() == null) continue;
                String fileName = cp.getFileName().toString();
                Matcher m = JAR_NAME_PATTERN.matcher(fileName);
                if (m.matches()) {
                    String artifactId = m.group(1);
                    String version = m.group(2);
                    coords.putIfAbsent(artifactId + ":" + version, new DependencyCoordinate("*", artifactId, version));
                }
            }
        }
        return List.copyOf(coords.values());
    }

    private static void parsePomDependencies(Path pomFile, Map<String, DependencyCoordinate> out) {
        if (!Files.isRegularFile(pomFile)) return;
        try {
            String content = Files.readString(pomFile);
            Matcher m = POM_DEP_PATTERN.matcher(content);
            while (m.find()) {
                String g = m.group(1).trim();
                String a = m.group(2).trim();
                String v = m.group(3) != null ? m.group(3).trim() : "";
                if (!v.isEmpty()) {
                    out.putIfAbsent(g + ":" + a + ":" + v, new DependencyCoordinate(g, a, v));
                }
            }
        } catch (IOException ignored) {}
    }

    private static void parseGradleDependencies(Path gradleFile, Map<String, DependencyCoordinate> out) {
        if (!Files.isRegularFile(gradleFile)) return;
        try {
            String content = Files.readString(gradleFile);
            Matcher m = GRADLE_DEP_PATTERN.matcher(content);
            while (m.find()) {
                String g = m.group(1).trim();
                String a = m.group(2).trim();
                String v = m.group(3).trim();
                out.putIfAbsent(g + ":" + a + ":" + v, new DependencyCoordinate(g, a, v));
            }
        } catch (IOException ignored) {}
    }

    private static void parseGradleLockfile(Path lockFile, Map<String, DependencyCoordinate> out) {
        if (!Files.isRegularFile(lockFile)) return;
        try {
            for (String line : Files.readAllLines(lockFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("empty=")) continue;
                int eq = trimmed.indexOf('=');
                String gav = eq > 0 ? trimmed.substring(0, eq) : trimmed;
                String[] parts = gav.split(":");
                if (parts.length == 3) {
                    out.putIfAbsent(gav, new DependencyCoordinate(parts[0], parts[1], parts[2]));
                }
            }
        } catch (IOException ignored) {}
    }

    private static List<MatchedVulnerability> parseExternalCveReport(Path file) {
        List<MatchedVulnerability> result = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(file.toFile());
            JsonNode vulnsNode = root.path("vulnerabilities");
            if (vulnsNode.isArray()) {
                for (JsonNode v : vulnsNode) {
                    String id = v.path("id").asText(v.path("cveId").asText("CVE-UNKNOWN"));
                    String dep = v.path("dependency").asText(v.path("packageName").asText("unknown:unknown"));
                    String ver = v.path("version").asText("0.0.0");
                    String fix = v.path("fixedVersion").asText("upgrade");
                    String sev = v.path("severity").asText("HIGH").toUpperCase(Locale.ROOT);
                    double cvss = v.path("cvssScore").asDouble(7.5);
                    String summary = v.path("summary").asText(v.path("title").asText("Reported vulnerability"));
                    List<String> prefixes = new ArrayList<>();
                    if (v.path("packagePrefixes").isArray()) {
                        v.path("packagePrefixes").forEach(p -> prefixes.add(p.asText()));
                    } else if (dep.contains(":")) {
                        prefixes.add(dep.substring(0, dep.indexOf(':')));
                    }
                    List<String> tokens = new ArrayList<>();
                    if (v.path("symbols").isArray()) {
                        v.path("symbols").forEach(s -> tokens.add(s.asText()));
                    }
                    result.add(new MatchedVulnerability(id, dep, ver, fix, sev, cvss, summary, prefixes, tokens));
                }
            }
            // Trivy Results[].Vulnerabilities[] format
            if (root.path("Results").isArray()) {
                for (JsonNode res : root.path("Results")) {
                    for (JsonNode v : res.path("Vulnerabilities")) {
                        String id = v.path("VulnerabilityID").asText("CVE-UNKNOWN");
                        String pkg = v.path("PkgName").asText("unknown");
                        String ver = v.path("InstalledVersion").asText("0.0.0");
                        String fix = v.path("FixedVersion").asText("upgrade");
                        String sev = v.path("Severity").asText("HIGH").toUpperCase(Locale.ROOT);
                        String title = v.path("Title").asText("Trivy vulnerability finding");
                        List<String> prefixes = pkg.contains(":") ? List.of(pkg.substring(0, pkg.indexOf(':'))) : List.of(pkg);
                        result.add(new MatchedVulnerability(id, pkg, ver, fix, sev, 7.5, title, prefixes, List.of()));
                    }
                }
            }
        } catch (IOException ignored) {}
        return result;
    }

    private static List<MatchedVulnerability> queryOsvBatchApi(List<DependencyCoordinate> dependencies) {
        List<MatchedVulnerability> found = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> queries = new ArrayList<>();
            List<DependencyCoordinate> queriedDeps = new ArrayList<>();
            for (DependencyCoordinate dep : dependencies) {
                if ("*".equals(dep.groupId())) continue;
                queries.add(Map.of(
                        "version", dep.version(),
                        "package", Map.of("name", dep.groupId() + ":" + dep.artifactId(), "ecosystem", "Maven")
                ));
                queriedDeps.add(dep);
            }
            if (queries.isEmpty()) return List.of();

            String payload = mapper.writeValueAsString(Map.of("queries", queries));
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.osv.dev/v1/querybatch"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode results = mapper.readTree(response.body()).path("results");
                for (int i = 0; i < results.size() && i < queriedDeps.size(); i++) {
                    DependencyCoordinate dep = queriedDeps.get(i);
                    for (JsonNode vuln : results.get(i).path("vulns")) {
                        String id = vuln.path("id").asText();
                        found.add(new MatchedVulnerability(
                                id,
                                dep.groupId() + ":" + dep.artifactId(),
                                dep.version(),
                                "latest",
                                "HIGH",
                                7.5,
                                "OSV advisory " + id + " affecting " + dep.groupId() + ":" + dep.artifactId(),
                                List.of(dep.groupId()),
                                List.of()
                        ));
                    }
                }
            }
        } catch (Exception ignored) {
            // Graceful fallback when running in sandboxed or air-gapped CI
        }
        return found;
    }

    private static Set<String> findDirectCallers(
            MatchedVulnerability vuln,
            Map<String, MethodModel> methodById,
            GlobalCallGraph callGraph) {

        Set<String> callers = new LinkedHashSet<>();
        for (Map.Entry<String, MethodModel> entry : methodById.entrySet()) {
            String methodId = entry.getKey();
            MethodModel method = entry.getValue();
            boolean callsVuln = false;

            for (SemanticCallSite callSite : method.getSemanticCallSites()) {
                if (callSite.getResolvedTarget().isPresent()) {
                    String declaringType = callSite.getResolvedTarget().get().declaringType();
                    String qualified = callSite.getResolvedTarget().get().qualifiedSignature();
                    if (vuln.packagePrefixes().stream().anyMatch(declaringType::startsWith)
                            || vuln.classOrMethodTokens().stream().anyMatch(qualified::contains)) {
                        callsVuln = true;
                        break;
                    }
                }
                String written = callSite.getWrittenName();
                if (vuln.classOrMethodTokens().stream().anyMatch(written::contains)) {
                    callsVuln = true;
                    break;
                }
            }

            if (!callsVuln && method.getControlFlowModel().isPresent()) {
                for (ControlFlowNode node : method.getControlFlowModel().get().getNodes().values()) {
                    String src = node.getSourceCode();
                    if (src != null && vuln.classOrMethodTokens().stream().anyMatch(src::contains)) {
                        callsVuln = true;
                        break;
                    }
                }
            }

            if (!callsVuln) {
                Set<String> callees = callGraph.getOutgoing().getOrDefault(methodId, Set.of());
                if (callees.stream().anyMatch(c -> matchesExternalNode(c, vuln))) {
                    callsVuln = true;
                }
            }

            if (callsVuln) {
                callers.add(methodId);
            }
        }
        return callers;
    }

    private static boolean matchesExternalNode(String nodeId, MatchedVulnerability vuln) {
        if (nodeId == null) return false;
        return vuln.packagePrefixes().stream().anyMatch(nodeId::contains)
                || vuln.classOrMethodTokens().stream().anyMatch(nodeId::contains);
    }

    private static boolean matchesCoordinate(DependencyCoordinate dep, String groupId, String artifactId) {
        if (!dep.artifactId().equalsIgnoreCase(artifactId)) return false;
        return "*".equals(dep.groupId()) || dep.groupId().equalsIgnoreCase(groupId);
    }

    static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("[.-]");
        String[] parts2 = v2.split("[.-]");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            String p1 = i < parts1.length ? parts1[i] : "0";
            String p2 = i < parts2.length ? parts2[i] : "0";
            try {
                int n1 = Integer.parseInt(p1);
                int n2 = Integer.parseInt(p2);
                if (n1 != n2) return Integer.compare(n1, n2);
            } catch (NumberFormatException e) {
                int cmp = p1.compareToIgnoreCase(p2);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    private record MatchedVulnerability(
            String cveId,
            String dependency,
            String version,
            String fixedVersion,
            String severity,
            double cvssScore,
            String summary,
            List<String> packagePrefixes,
            List<String> classOrMethodTokens
    ) {}
}
