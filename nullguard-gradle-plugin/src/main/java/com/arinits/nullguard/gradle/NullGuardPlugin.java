package com.arinits.nullguard.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaToolchainService;
import java.util.List;

/** Adds NullGuard to Java projects without adding the analyzer to application dependencies. */
public final class NullGuardPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        NullGuardExtension extension = project.getExtensions().create("nullguard", NullGuardExtension.class);
        extension.getToolVersion().convention("2.0.1");
        extension.getFailBuild().convention(false);
        extension.getFailOnApiCve().convention(false);
        extension.getFailOnIncomplete().convention(false);
        extension.getFailThreshold().convention("CRITICAL");
        extension.getRunOnCheck().convention(false);
        extension.getReportDirectory().convention(project.getLayout().getBuildDirectory().dir("reports/nullguard"));
        extension.getExcludes().convention(List.of());
        extension.getMaxHeapSize().convention("2g");
        extension.getCveOnline().convention(true);
        Configuration tool = project.getConfigurations().create("nullguardTool", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            // Use the verified standalone distribution so application BOMs cannot
            // downgrade SpotBugs, BCEL, Jackson or their runtime dependencies.
            c.setTransitive(false);
            c.setDescription("NullGuard CLI runtime, isolated from application dependencies");
        });
        project.getDependencies().addProvider(tool.getName(), extension.getToolVersion().map(version ->
                "com.arinits.nullguard:nullguard-cli:" + version + ":jar-with-dependencies"));
        project.getPluginManager().withPlugin("java", ignored -> {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            TaskProvider<NullGuardTask> analyze = project.getTasks().register("nullguard", NullGuardTask.class, task -> {
                task.setGroup("verification");
                task.setDescription("Analyze main Java sources and write NullGuard reports");
                task.getMainClass().set("com.nullguard.cli.NullGuardCliApplication");
                task.setClasspath(tool);
                task.getJavaLauncher().convention(toolchains.launcherFor(spec ->
                        spec.getLanguageVersion().set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))));
                task.getProjectDirectory().convention(project.getLayout().getProjectDirectory());
                task.getSourceRoots().from(main.getJava().getSrcDirs());
                task.getSourceFiles().from(main.getAllJava());
                task.getAnalysisClasspath().from(main.getCompileClasspath());
                task.getSastClassDirectories().from(main.getOutput().getClassesDirs().getFiles());
                task.getReportDirectory().convention(extension.getReportDirectory());
                task.getFailBuild().convention(extension.getFailBuild());
                task.getFailOnApiCve().convention(extension.getFailOnApiCve());
                task.getFailOnIncomplete().convention(extension.getFailOnIncomplete());
                task.getBaseline().convention(extension.getBaseline());
                task.getFailThreshold().convention(extension.getFailThreshold());
                task.getExcludes().convention(extension.getExcludes());
                task.getAnalyzerHeapSize().convention(extension.getMaxHeapSize());
                task.getOpenApiConfig().convention(extension.getOpenApiConfig());
                task.getCveOnline().convention(extension.getCveOnline());
                task.getCveInput().convention(extension.getCveInput());
                task.getJacocoReport().convention(extension.getJacocoReport());
            });
            project.getTasks().named("check").configure(task -> task.dependsOn(
                    extension.getRunOnCheck().map(enabled -> enabled ? List.of(analyze) : List.of())));
        });
    }
}
