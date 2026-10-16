package com.arinits.nullguard.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.DuplicatesStrategy;
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

/** Runs the published CLI once over staged Java source roots. */
@DisableCachingByDefault(because = "Reports include timestamps and absolute source URIs")
public abstract class NullGuardTask extends JavaExec {
    @InputFiles @SkipWhenEmpty @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();
    @Classpath public abstract ConfigurableFileCollection getAnalysisClasspath();
    @Input public abstract Property<Boolean> getFailBuild();
    @Input public abstract Property<String> getFailThreshold();
    @Input public abstract ListProperty<String> getExcludes();
    @Input public abstract Property<String> getAnalyzerHeapSize();
    @OutputDirectory public abstract DirectoryProperty getReportDirectory();
    @Inject protected abstract FileSystemOperations getFileSystemOperations();

    @Override @TaskAction
    public void exec() {
        String threshold = getFailThreshold().get().toUpperCase(Locale.ROOT);
        if (threshold.equals("MEDIUM")) threshold = "MODERATE";
        if (!List.of("LOW", "MODERATE", "HIGH", "CRITICAL").contains(threshold))
            throw new GradleException("NullGuard failThreshold must be LOW, MEDIUM, MODERATE, HIGH or CRITICAL");
        if (getJavaLauncher().get().getMetadata().getLanguageVersion().asInt() < 17)
            throw new GradleException("NullGuard requires a Java 17+ launcher. Configure the Java toolchain or nullguard task javaLauncher.");
        File staged = new File(getTemporaryDir(), "sources");
        getFileSystemOperations().sync(copy -> {
            copy.from(getSourceFiles());
            copy.into(staged);
            copy.include("**/*.java");
            copy.exclude(getExcludes().get());
            copy.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
        });
        List<String> arguments = new ArrayList<>();
        arguments.add(staged.getAbsolutePath());
        arguments.add("--classpath=" + getAnalysisClasspath().getAsPath());
        arguments.add("--output=" + getReportDirectory().get().getAsFile().getAbsolutePath());
        arguments.add("--fail-threshold=" + threshold);
        if (getFailBuild().get()) arguments.add("--fail-build");
        setArgs(arguments);
        setMaxHeapSize(getAnalyzerHeapSize().get());
        File reports = getReportDirectory().get().getAsFile();
        // Remove only known analyzer outputs so a failed run cannot appear to be a fresh clean report.
        getFileSystemOperations().delete(spec -> spec.delete(
                new File(reports, "nullguard-report-latest.json"), new File(reports, "nullguard-results.sarif"),
                new File(reports, "nullguard-graph-latest.dot"), new File(reports, "nullguard-dashboard-latest.html"),
                new File(reports, "nullguard-openapi.json")));
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
