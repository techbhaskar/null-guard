package com.nullguard.bootstrap;

import edu.umd.cs.findbugs.BugCollectionBugReporter;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.DetectorFactoryCollection;
import edu.umd.cs.findbugs.FindBugs2;
import edu.umd.cs.findbugs.Plugin;
import edu.umd.cs.findbugs.Project;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.config.UserPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs SpotBugs and FindSecBugs against compiled application classes. */
final class SastScanner {
    private static final String TOOL = "SpotBugs + FindSecBugs";

    private SastScanner() {}

    static Map<String, Object> scan(NullGuardConfig config) {
        List<Path> classDirectories = config.getSastClassDirectories();
        if (classDirectories.isEmpty()) {
            return notRun("No compiled class directories were configured.");
        }

        int classCount = 0;
        for (Path directory : classDirectories) {
            if (!Files.isDirectory(directory)) {
                return notRun("Configured SAST class directory is unavailable: " + directory);
            }
            try (var files = Files.walk(directory)) {
                classCount += (int) files.filter(path -> Files.isRegularFile(path)
                        && path.getFileName().toString().endsWith(".class")).count();
            } catch (IOException e) {
                throw new IllegalStateException("Could not inspect SAST class directory: " + directory, e);
            }
        }
        if (classCount == 0) {
            return notRun("Configured class directories contain no compiled .class files.");
        }

        Project project = new Project();
        classDirectories.forEach(directory -> project.addFile(directory.toAbsolutePath().toString()));
        config.getClasspathEntries().stream()
                .filter(Files::exists)
                .forEach(entry -> project.addAuxClasspathEntry(entry.toAbsolutePath().toString()));

        Plugin findSecBugs = null;
        PluginArtifact pluginArtifact = null;
        try {
            pluginArtifact = findSecBugsPlugin();
            findSecBugs = Plugin.loadCustomPlugin(pluginArtifact.url(), project);
            if (findSecBugs == null) {
                throw new IllegalStateException("FindSecBugs plugin was not loaded from " + pluginArtifact.url());
            }

            List<String> scanErrors = new ArrayList<>();
            BugCollectionBugReporter reporter = new BugCollectionBugReporter(project) {
                @Override
                public void logError(String message) {
                    scanErrors.add(message);
                    super.logError(message);
                }

                @Override
                public void logError(String message, Throwable cause) {
                    scanErrors.add(message + ": " + cause);
                    super.logError(message, cause);
                }
            };
            reporter.setPriorityThreshold(3);
            int missingClassCount;
            try (FindBugs2 engine = new FindBugs2()) {
                engine.setProject(project);
                engine.setBugReporter(reporter);
                engine.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
                engine.setUserPreferences(UserPreferences.createDefaultUserPreferences());
                engine.setNoClassOk(true);
                engine.execute();
                missingClassCount = engine.getMissingClassCount();
            }
            return completed(classCount, missingClassCount, scanErrors,
                    reporter.getBugCollection().getCollection());
        } catch (Exception e) {
            if (e instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("SpotBugs + FindSecBugs SAST scan failed.", e);
        } finally {
            IOException cleanupFailure = null;
            if (findSecBugs != null) {
                Plugin.removeCustomPlugin(findSecBugs);
                try {
                    findSecBugs.close();
                } catch (IOException e) {
                    cleanupFailure = e;
                }
            }
            if (pluginArtifact != null && pluginArtifact.temporaryJar() != null) {
                try {
                    Files.deleteIfExists(pluginArtifact.temporaryJar());
                } catch (IOException e) {
                    if (cleanupFailure == null) cleanupFailure = e;
                    else cleanupFailure.addSuppressed(e);
                }
            }
            if (cleanupFailure != null) {
                throw new IllegalStateException("Could not release the FindSecBugs plugin.", cleanupFailure);
            }
        }
    }

    private static PluginArtifact findSecBugsPlugin() throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = SastScanner.class.getClassLoader();
        Enumeration<URL> descriptors = classLoader.getResources("findbugs.xml");
        while (descriptors.hasMoreElements()) {
            URL descriptor = descriptors.nextElement();
            if ("jar".equals(descriptor.getProtocol())) {
                String contents;
                try (InputStream input = descriptor.openStream()) {
                    contents = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (contents.contains("pluginid=\"com.h3xstream.findsecbugs\"")) {
                    URL jar = ((JarURLConnection) descriptor.openConnection()).getJarFileURL();
                    return new PluginArtifact(jar, null);
                }
            }
        }
        String nestedJarPath = "META-INF/nullguard/findsecbugs-plugin.jar";
        URL nestedJar = classLoader.getResource(nestedJarPath);
        if (nestedJar != null) {
            Path temporaryJar = Files.createTempFile("nullguard-findsecbugs-", ".jar");
            try (InputStream input = nestedJar.openStream()) {
                Files.copy(input, temporaryJar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(temporaryJar);
                throw e;
            }
            return new PluginArtifact(temporaryJar.toUri().toURL(), temporaryJar);
        }
        throw new IllegalStateException("FindSecBugs plugin JAR is not available on the runtime classpath.");
    }

    private static Map<String, Object> completed(int classCount, int missingClassCount,
                                                  List<String> scanErrors, Iterable<BugInstance> bugs) {
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Integer> severityCounts = new LinkedHashMap<>(Map.of(
                "HIGH", 0, "MEDIUM", 0, "LOW", 0));
        for (BugInstance bug : bugs) {
            int priority = bug.getPriority();
            String severity = priority <= 1 ? "HIGH" : priority == 2 ? "MEDIUM" : "LOW";
            severityCounts.compute(severity, (key, count) -> count + 1);
            SourceLineAnnotation source = bug.getPrimarySourceLineAnnotation();
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("type", bug.getType());
            finding.put("category", bug.getBugPattern().getCategory());
            finding.put("priority", priority);
            finding.put("severity", severity);
            finding.put("message", bug.getMessage());
            finding.put("path", source == null ? "" : source.getSourcePath());
            finding.put("line", source == null ? 0 : source.getStartLine());
            findings.add(finding);
        }
        findings.sort(java.util.Comparator
                .comparing((Map<String, Object> finding) -> (String) finding.get("path"))
                .thenComparing(finding -> (Integer) finding.get("line"))
                .thenComparing(finding -> (String) finding.get("type")));
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (missingClassCount > 0) {
            warnings.add("SpotBugs could not resolve " + missingClassCount
                    + " dependency class(es); some detectors may have reduced coverage.");
        }
        if (!scanErrors.isEmpty()) {
            warnings.add("SpotBugs reported " + scanErrors.size()
                    + " analysis error(s); first error: " + scanErrors.get(0));
        }
        summary.put("status", warnings.isEmpty() ? "COMPLETED" : "PARTIAL");
        summary.put("tool", TOOL);
        summary.put("scannedClassCount", classCount);
        summary.put("missingClassCount", missingClassCount);
        summary.put("findingCount", findings.size());
        summary.put("severityCounts", severityCounts);
        summary.put("findings", findings);
        summary.put("warnings", warnings);
        return summary;
    }

    private static Map<String, Object> notRun(String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "NOT_RUN");
        summary.put("tool", TOOL);
        summary.put("reason", reason);
        summary.put("scannedClassCount", 0);
        summary.put("findingCount", 0);
        summary.put("severityCounts", Map.of("HIGH", 0, "MEDIUM", 0, "LOW", 0));
        summary.put("findings", List.of());
        return summary;
    }

    private record PluginArtifact(URL url, Path temporaryJar) {}
}
