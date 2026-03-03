package com.nullguard.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY)
public class NullGuardAnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("-------------------------------------------------------");
        getLog().info(" NullGuard: Stability Intelligence Engine (v1.0)");
        getLog().info("-------------------------------------------------------");
        getLog().info("Analyzing project: " + project.getName());
        getLog().info("Source directory: " + project.getBuild().getSourceDirectory());
        
        try {
            java.nio.file.Path sourcePath = java.nio.file.Paths.get(project.getBuild().getSourceDirectory());
            
            if (!java.nio.file.Files.exists(sourcePath)) {
                getLog().info("Source directory does not exist, skipping analysis: " + sourcePath);
                return;
            }
            
            // 1. Parse AST
            getLog().info("Parsing Source Trees...");
            com.nullguard.core.parser.JavaParserAstParser parser = new com.nullguard.core.parser.JavaParserAstParser();
            com.nullguard.core.model.ProjectModel projectModel = parser.parse(sourcePath);
            
            // 2. Build Call Graph
            getLog().info("Building Global Call Graph...");
            com.nullguard.callgraph.builder.BasicCallGraphBuilder cgBuilder = new com.nullguard.callgraph.builder.BasicCallGraphBuilder();
            com.nullguard.callgraph.model.GlobalCallGraph callGraph = cgBuilder.build(projectModel);
            
            // 3. Propagate Risk
            getLog().info("Propagating Risk Scores...");
            com.nullguard.scoring.propagation.FixpointRiskPropagationEngine riskEngine = new com.nullguard.scoring.propagation.FixpointRiskPropagationEngine();
            com.nullguard.scoring.config.ScoringConfig config = com.nullguard.scoring.config.ScoringConfig.builder()
                .decayFactor(0.85)
                .externalPenaltyMultiplier(1.2)
                .convergenceThreshold(0.001)
                .maxIterations(100)
                .build();
            java.util.Map<String, com.nullguard.scoring.model.AdjustedRiskModel> riskModels = riskEngine.propagate(projectModel, callGraph, config);
            
            getLog().info("Risk models computed for " + riskModels.size() + " methods.");
            
        } catch (Exception e) {
            getLog().error("NullGuard analysis failed", e);
            throw new MojoExecutionException("Error during NullGuard analysis", e);
        }

        getLog().info("NullGuard analysis completed successfully.");
    }
}
