package com.arinits.nullguard.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;
import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runs the published CLI once over original Java source files. */
@DisableCachingByDefault(because = "Reports include timestamps and absolute source URIs")
public abstract class NullGuardTask extends JavaExec {
    public NullGuardTask() {
        // Build-cache disabling alone does not disable Gradle's up-to-date checks.
        // Advisory data and report timestamps must refresh even when sources are unchanged.
        getOutputs().upToDateWhen(task -> false);
    }

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();
    @Internal public abstract DirectoryProperty getProjectDirectory();
    @Internal public abstract ConfigurableFileCollection getSourceRoots();
    @Classpath public abstract ConfigurableFileCollection getAnalysisClasspath();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSastClassDirectories();
    @Input public abstract Property<Boolean> getFailBuild();
    @Input public abstract Property<Boolean> getFailOnApiCve();
    @Input public abstract Property<Boolean> getFailOnIncomplete();
    @Optional @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.RegularFileProperty getBaseline();
    @Input public abstract Property<String> getFailThreshold();
    @Input public abstract ListProperty<String> getExcludes();
    @Input public abstract Property<String> getAnalyzerHeapSize();
    @Input public abstract Property<Boolean> getCveOnline();
    @Optional @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.RegularFileProperty getCveInput();
    @Optional @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.RegularFileProperty getJacocoReport();
    @Optional @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract org.gradle.api.file.RegularFileProperty getOpenApiConfig();
    @OutputDirectory public abstract DirectoryProperty getReportDirectory();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    @Override @TaskAction
    public void exec() {
        String threshold = getFailThreshold().get().toUpperCase(Locale.ROOT);
        if (threshold.equals("MEDIUM")) threshold = "MODERATE";
        if (!List.of("LOW", "MODERATE", "HIGH", "CRITICAL").contains(threshold))
            throw new GradleException("NullGuard failThreshold must be LOW, MEDIUM, MODERATE, HIGH or CRITICAL");
        if (getJavaLauncher().get().getMetadata().getLanguageVersion().asInt() < 21)
            throw new GradleException("NullGuard requires a Java 21+ launcher. Configure the Java toolchain or nullguard task javaLauncher.");
        var selected = getSourceFiles().getAsFileTree().matching(patterns -> {
            patterns.include("**/*.java");
            patterns.exclude(getExcludes().get());
        }).getFiles().stream().map(File::toPath).map(path -> path.toAbsolutePath().normalize()).sorted().toList();
        File reports = getReportDirectory().get().getAsFile();
        // Remove only known analyzer outputs so a failed run cannot appear to be a fresh clean report.
        getFileSystemOperations().delete(spec -> spec.delete(
                new File(reports, "nullguard-report-latest.json"), new File(reports, "nullguard-results.sarif"),
                new File(reports, "nullguard-graph-latest.dot"), new File(reports, "nullguard-dashboard-latest.html"),
                new File(reports, "nullguard-openapi.json")));
        if (selected.isEmpty()) {
            if (getFailOnIncomplete().get()) throw new GradleException("NullGuard source analysis status: NO_SOURCE");
            setDidWork(false);
            return;
        }
        var manifest = new java.util.Properties();
        for (int i = 0; i < selected.size(); i++) manifest.setProperty("file." + i, selected.get(i).toString());
        var roots = getSourceRoots().getFiles().stream().map(File::getAbsolutePath).sorted().toList();
        for (int i = 0; i < roots.size(); i++) manifest.setProperty("root." + i, roots.get(i));
        File manifestFile = new File(getTemporaryDir(), "source-manifest.properties");
        try {
            java.nio.file.Files.createDirectories(manifestFile.toPath().getParent());
            try (var writer = java.nio.file.Files.newBufferedWriter(manifestFile.toPath())) {
                manifest.store(writer, "Original Java source paths; source contents are not copied");
            }
        } catch (java.io.IOException failure) { throw new GradleException("Cannot write source path manifest", failure); }
        List<String> arguments = new ArrayList<>();
        arguments.add(getProjectDirectory().get().getAsFile().getAbsolutePath());
        arguments.add("--source-manifest=" + manifestFile.getAbsolutePath());
        arguments.add("--classpath=" + getAnalysisClasspath().getAsPath());
        String sastClassPath = getSastClassDirectories().getFiles().stream()
                .filter(File::isDirectory)
                .map(File::getAbsolutePath)
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        arguments.add("--output=" + getReportDirectory().get().getAsFile().getAbsolutePath());
        arguments.add("--fail-threshold=" + threshold);
        if (getOpenApiConfig().isPresent()) arguments.add("--openapi-config=" + getOpenApiConfig().get().getAsFile().getAbsolutePath());
        if (getCveOnline().get()) arguments.add("--cve-online");
        if (getJacocoReport().isPresent()) arguments.add("--jacoco-report=" + getJacocoReport().get().getAsFile().getAbsolutePath());
        if (getCveInput().isPresent()) arguments.add("--cve-input=" + getCveInput().get().getAsFile().getAbsolutePath());
        if (getFailBuild().get()) arguments.add("--fail-build");
        if (getFailOnApiCve().get()) arguments.add("--fail-on-api-cve");
        if (getFailOnIncomplete().get()) arguments.add("--fail-on-incomplete");
        if (getBaseline().isPresent()) arguments.add("--baseline=" + getBaseline().get().getAsFile().getAbsolutePath());
        setArgs(arguments);
        java.util.Map<String, Object> systemProperties = new java.util.HashMap<>(getSystemProperties());
        systemProperties.put("nullguard.sast.classes", sastClassPath);
        systemProperties.put("nullguard.cve.online", getCveOnline().get().toString());
        setSystemProperties(systemProperties);
        setMaxHeapSize(getAnalyzerHeapSize().get());
        setIgnoreExitValue(true);
        super.exec();
        int exit = getExecutionResult().get().getExitValue();
        try {
            if (new File(reports, "nullguard-report-latest.json").isFile()) {
                if (!new File(reports, "nullguard-dashboard-latest.html").isFile()) HtmlReport.write(reports.toPath());
            } else if (exit == 0) throw new GradleException("NullGuard completed without producing its JSON report");
        } catch (java.io.IOException failure) {
            throw new GradleException("Could not write the NullGuard HTML report", failure);
        }
        if (exit != 0) throw new GradleException("NullGuard exited with code " + exit + ". See analyzer output and reports in " + reports);
        getLogger().lifecycle("NullGuard reports: {}", getReportDirectory().get().getAsFile());
    }
}
