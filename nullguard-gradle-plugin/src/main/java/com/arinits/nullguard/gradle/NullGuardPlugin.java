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
        extension.getToolVersion().convention("1.1.0");
        extension.getFailBuild().convention(false);
        extension.getFailThreshold().convention("CRITICAL");
        extension.getRunOnCheck().convention(false);
        extension.getReportDirectory().convention(project.getLayout().getBuildDirectory().dir("reports/nullguard"));
        extension.getExcludes().convention(List.of());
        extension.getMaxHeapSize().convention("2g");
        Configuration tool = project.getConfigurations().create("nullguardTool", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setTransitive(true);
            c.setDescription("NullGuard CLI runtime, isolated from application dependencies");
        });
        project.getDependencies().addProvider(tool.getName(), extension.getToolVersion().map(version ->
                "com.arinits.nullguard:nullguard-cli:" + version));
        project.getPluginManager().withPlugin("java", ignored -> {
            JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            TaskProvider<NullGuardTask> analyze = project.getTasks().register("nullguard", NullGuardTask.class, task -> {
                task.setGroup("verification");
                task.setDescription("Analyze main Java sources and write NullGuard reports");
                task.getMainClass().set("com.nullguard.cli.NullGuardCliApplication");
                task.setClasspath(tool);
                task.getJavaLauncher().convention(toolchains.launcherFor(java.getToolchain()));
                task.getSourceFiles().from(main.getAllJava());
                task.getAnalysisClasspath().from(main.getCompileClasspath());
                task.getReportDirectory().convention(extension.getReportDirectory());
                task.getFailBuild().convention(extension.getFailBuild());
                task.getFailThreshold().convention(extension.getFailThreshold());
                task.getExcludes().convention(extension.getExcludes());
                task.getAnalyzerHeapSize().convention(extension.getMaxHeapSize());
                task.getOpenApiConfig().convention(extension.getOpenApiConfig());
            });
            project.getTasks().named("check").configure(task -> task.dependsOn(
                    extension.getRunOnCheck().map(enabled -> enabled ? List.of(analyze) : List.of())));
        });
    }
}
