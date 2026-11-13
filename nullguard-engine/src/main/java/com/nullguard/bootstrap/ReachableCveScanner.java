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
import com.nullguard.security.OsvClient;
import java.time.Instant;
import java.util.Properties;
import java.util.jar.JarFile;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public record ScanOutcome(List<Finding> findings, Map<String, Object> cveSummary,
                              List<BuildPolicy.Candidate> gateCandidates) {}

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

        return scan(projectRoot, config, project, callGraph, endpoints, new OsvClient());
    }

    static ScanOutcome scan(Path projectRoot, NullGuardConfig config, ProjectModel project,
            GlobalCallGraph callGraph, List<ApiEndpointModel> endpoints, OsvClient osv) {
        List<DependencyCoordinate> dependencies = discoverDependencies(projectRoot, config);
        List<MatchedVulnerability> matched = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        String checkedAt = Instant.now().toString();
        boolean onlineCompleted = false;
        int queriedDependencies = 0;
        if (config.isOsvOnlineLookup()) {
            List<OsvClient.Package> queries = dependencies.stream()
                    .filter(dep -> !dep.groupId().equals("*") && !dep.version().contains("${"))
                    .map(dep -> new OsvClient.Package("Maven", dep.groupId() + ":" + dep.artifactId(), dep.version()))
                    .distinct().toList();
            queriedDependencies = queries.size();
            if (queries.size() < dependencies.size())
                diagnostics.add("Some dependency coordinates could not be resolved for OSV lookup.");
            if (!queries.isEmpty()) {
                try {
                    for (OsvClient.Match match : osv.query(queries)) {
                        JsonNode detail = OsvClient.metadata(match);
                        String id = match.advisory().path("id").asText();
                        String name = match.dependency().name();
                        List<String> fixes = new ArrayList<>();
                        detail.path("fixedVersions").forEach(v -> fixes.add(v.asText()));
                        matched.add(new MatchedVulnerability(id, name, match.dependency().version(),
                                fixes.isEmpty() ? "Not provided; review advisory" : String.join(", ", fixes),
                                detail.path("severity").asText("UNKNOWN"), null,
                                detail.path("summary").asText(), List.of(), List.of()));
                        metadata.put(id + "@" + name + "@" + match.dependency().version(),
                                new ObjectMapper().convertValue(detail, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                    }
                    onlineCompleted = true;
                    sources.add("OSV_LIVE");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    diagnostics.add("OSV lookup interrupted; advisory coverage is incomplete.");
                } catch (Exception failure) {
                    diagnostics.add("OSV lookup incomplete: " + failure.getMessage());
                }
            } else diagnostics.add("No fully resolved dependency coordinates available for online lookup.");
        }
        // The small bundled catalog is offline fallback evidence, never a current database.
        if (!onlineCompleted) {
            sources.add("BUNDLED_LEGACY_CATALOG");
            for (DependencyCoordinate dep : dependencies) {
                for (AdvisoryEntry adv : BUILTIN_CATALOG) {
                    if (matchesCoordinate(dep, adv.groupId(), adv.artifactId())
                            && compareVersions(dep.version(), adv.maxVulnerableVersion()) <= 0) {
                        matched.add(new MatchedVulnerability(adv.cveId(), dep.groupId() + ":" + dep.artifactId(),
                                dep.version(), adv.fixedVersion(), adv.severity(), adv.cvssScore(), adv.summary(),
                                adv.packagePrefixes(), adv.classOrMethodTokens()));
                    }
                }
            }
        }
        if (config.getCveInputFile() != null) {
            try {
                matched.addAll(parseExternalCveReport(config.getCveInputFile()));
                sources.add("EXTERNAL_REPORT");
            } catch (Exception failure) {
                diagnostics.add("External CVE input could not be read: " + failure.getMessage());
            }
        }
        String status = dependencies.isEmpty() && config.getCveInputFile() == null ? "NOT_RUN"
                : !diagnostics.isEmpty() ? "PARTIAL" : dependencies.isEmpty() ? "NOT_RUN"
                : onlineCompleted ? "COMPLETED" : "OFFLINE_LIMITED";

        // Deduplicate by CVE ID + dependency
        Map<String, MatchedVulnerability> uniqueVulns = new LinkedHashMap<>();
        for (MatchedVulnerability v : matched) {
            uniqueVulns.merge(v.cveId() + "@" + v.dependency() + "@" + v.version(), v,
                    (first, next) -> next.affectedMethods().isEmpty() ? first : next);
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
        List<BuildPolicy.Candidate> gateCandidates = new ArrayList<>();
        int apiReachableCount = 0;
        int codeReachableCount = 0;
        int unreachableCount = 0;

        for (MatchedVulnerability vuln : uniqueVulns.values()) {
            Set<String> directCallerMethods = findDirectCallers(vuln, methodById, callGraph);
            List<String> affectedEndpoints = new ArrayList<>();
            Map<String, Set<String>> exactCalls = new LinkedHashMap<>();
            Set<String> resolvedEndpoints = new LinkedHashSet<>();
            if (!vuln.affectedMethods().isEmpty()) {
                methodById.forEach((id, method) -> {
                    for (SemanticCallSite site : method.getSemanticCallSites()) {
                        var target = site.getResolvedTarget().orElse(null);
                        if (target == null) continue;
                        String signature = target.declaringType() + MethodIds.SIGNATURE_SEPARATOR
                                + target.canonicalSignature().replace(" ", "");
                        if (vuln.affectedMethods().contains(signature)) {
                            exactCalls.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(signature);
                        }
                    }
                });
                directCallerMethods.addAll(exactCalls.keySet());
            }
            for (ApiEndpointModel ep : endpoints) {
                boolean hitsChain = ep.getPropagationChain().stream().anyMatch(node ->
                        directCallerMethods.contains(node) || matchesExternalNode(node, vuln));
                for (String caller : ep.getPropagationChain()) {
                    for (String signature : exactCalls.getOrDefault(caller, Set.of())) {
                        String route = ep.getHttpMethod() + " " + ep.getPath();
                        resolvedEndpoints.add(route);
                        gateCandidates.add(new BuildPolicy.Candidate("NG401|" + vuln.cveId() + "|" + vuln.dependency()
                                + "|" + vuln.version() + "|" + route + "|" + ep.getPropagationChain().getFirst()
                                + "|" + signature, "CRITICAL"));
                    }
                }
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
            rec.put("source", "BUNDLED_LEGACY_OR_EXTERNAL");
            rec.putAll(metadata.getOrDefault(vuln.cveId() + "@" + vuln.dependency() + "@" + vuln.version(), Map.of()));
            rec.put("cveId", vuln.cveId());
            rec.put("dependency", vuln.dependency());
            rec.put("version", vuln.version());
            rec.put("fixedVersion", vuln.fixedVersion());
            rec.put("severity", vuln.severity());
            rec.put("cvssScore", vuln.cvssScore());
            rec.put("reachability", reachability);
            rec.put("reachabilityEvidence", !exactCalls.isEmpty() ? "RESOLVED_AFFECTED_METHOD"
                    : directCallerMethods.isEmpty() && affectedEndpoints.isEmpty() ? "NO_OBSERVED_PATH" : "HEURISTIC");
            rec.put("ruleId", ruleId);
            rec.put("summary", vuln.summary());
            rec.put("affectedEndpoints", affectedEndpoints.stream().distinct().toList());
            rec.put("resolvedAffectedMethodEndpoints", List.copyOf(resolvedEndpoints));
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
        cveSummary.put("status", status);
        cveSummary.put("checkedAt", checkedAt);
        cveSummary.put("sources", sources);
        cveSummary.put("onlineRequested", config.isOsvOnlineLookup());
        cveSummary.put("queriedDependencies", queriedDependencies);
        cveSummary.put("diagnostics", diagnostics);
        cveSummary.put("dependencyCoverage", "Discovered manifests and classpath only; not a complete SBOM");
        cveSummary.put("reachabilityLimit", "Static call-path evidence is not proof of exploitability; an unobserved path may exist at runtime");
        cveSummary.put("securityScore", status.equals("COMPLETED") ? securityScore : null);
        cveSummary.put("securityGrade", status.equals("COMPLETED") ? securityGrade : "N/A");
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

        return new ScanOutcome(sortedFindings, cveSummary, List.copyOf(gateCandidates));
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
                boolean identified = false;
                if (Files.isRegularFile(cp)) {
                    try (JarFile jar = new JarFile(cp.toFile())) {
                        var entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            if (!entry.getName().startsWith("META-INF/maven/") || !entry.getName().endsWith("/pom.properties")) continue;
                            Properties props = new Properties();
                            try (var input = jar.getInputStream(entry)) { props.load(input); }
                            String group = props.getProperty("groupId", "");
                            String artifact = props.getProperty("artifactId", "");
                            String version = props.getProperty("version", "");
                            if (!group.isBlank() && !artifact.isBlank() && !version.isBlank()) {
                                coords.putIfAbsent(group + ":" + artifact + ":" + version,
                                        new DependencyCoordinate(group, artifact, version));
                                identified = true;
                            }
                        }
                    } catch (IOException ignored) { /* Report unresolved coordinates below. */ }
                }
                Matcher m = JAR_NAME_PATTERN.matcher(fileName);
                if (!identified && m.matches()) {
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

    private static List<MatchedVulnerability> parseExternalCveReport(Path file) throws IOException {
        List<MatchedVulnerability> result = new ArrayList<>();
        JsonNode root = new ObjectMapper().readTree(file.toFile());
        if (root == null || !root.isObject() || (!root.path("vulnerabilities").isArray() && !root.path("Results").isArray()))
            throw new IOException("Expected vulnerabilities[] or Trivy Results[]");
        JsonNode vulnsNode = root.path("vulnerabilities");
        if (vulnsNode.isArray()) {
            for (JsonNode v : vulnsNode) {
                String id = v.path("id").asText(v.path("cveId").asText("CVE-UNKNOWN"));
                String dep = v.path("dependency").asText(v.path("packageName").asText("unknown:unknown"));
                String ver = v.path("version").asText("0.0.0");
                String fix = v.path("fixedVersion").asText("Not provided; review advisory");
                String sev = v.path("severity").asText("UNKNOWN").toUpperCase(Locale.ROOT);
                Double cvss = v.path("cvssScore").isNumber() ? v.path("cvssScore").asDouble() : null;
                String summary = v.path("summary").asText(v.path("title").asText("Reported vulnerability"));
                List<String> prefixes = new ArrayList<>();
                if (v.path("packagePrefixes").isArray()) {
                    v.path("packagePrefixes").forEach(p -> { if (p.isTextual() && !p.asText().isBlank()) prefixes.add(p.asText()); });
                }
                List<String> tokens = new ArrayList<>();
                if (v.path("symbols").isArray()) {
                    v.path("symbols").forEach(s -> { if (s.isTextual() && !s.asText().isBlank()) tokens.add(s.asText()); });
                }
                Set<String> affectedMethods = new LinkedHashSet<>();
                if (v.has("affectedMethods")) {
                    if (!v.path("affectedMethods").isArray()) throw new IOException("Expected affectedMethods array");
                    for (JsonNode method : v.path("affectedMethods")) {
                        if (!method.isTextual() || !method.asText().matches("[\\w.$]+#[\\w$<>]+\\([^()]*\\)"))
                            throw new IOException("Expected affected method in owner#method(parameter.types) form");
                        affectedMethods.add(method.asText().replace(" ", ""));
                    }
                }
                result.add(new MatchedVulnerability(id, dep, ver, fix, sev, cvss, summary, prefixes, tokens, Set.copyOf(affectedMethods)));
            }
        }
        // Trivy Results[].Vulnerabilities[] format
        if (root.path("Results").isArray()) {
            for (JsonNode res : root.path("Results")) {
                for (JsonNode v : res.path("Vulnerabilities")) {
                    String id = v.path("VulnerabilityID").asText("CVE-UNKNOWN");
                    String pkg = v.path("PkgName").asText("unknown");
                    String ver = v.path("InstalledVersion").asText("0.0.0");
                    String fix = v.path("FixedVersion").asText("Not provided; review advisory");
                    String sev = v.path("Severity").asText("UNKNOWN").toUpperCase(Locale.ROOT);
                    String title = v.path("Title").asText("Trivy vulnerability finding");
                    result.add(new MatchedVulnerability(id, pkg, ver, fix, sev, null, title, List.of(), List.of()));
                }
            }
        }
        return result;
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
                    if (vuln.packagePrefixes().stream().anyMatch(prefix -> !prefix.isBlank() && declaringType.startsWith(prefix + "."))
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
        return nodeId.startsWith(MethodIds.EXTERNAL_PREFIX) && vuln.classOrMethodTokens().stream().anyMatch(nodeId::contains);
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
            Double cvssScore,
            String summary,
            List<String> packagePrefixes,
            List<String> classOrMethodTokens,
            Set<String> affectedMethods
    ) {
        MatchedVulnerability(String cveId, String dependency, String version, String fixedVersion,
                             String severity, Double cvssScore, String summary, List<String> packagePrefixes,
                             List<String> classOrMethodTokens) {
            this(cveId, dependency, version, fixedVersion, severity, cvssScore, summary,
                    packagePrefixes, classOrMethodTokens, Set.of());
        }
    }
}
