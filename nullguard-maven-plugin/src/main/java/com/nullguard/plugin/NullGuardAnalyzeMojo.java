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
            
            // 4. Export visualization
            getLog().info("Generating analysis report...");
            java.io.File targetDir = new java.io.File(project.getBuild().getDirectory(), "nullguard");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            
            // Generate timestamp for history tracking
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = sdf.format(new java.util.Date());
            
            com.nullguard.visualization.graph.DefaultPropagationGraphBuilder visualBuilder = new com.nullguard.visualization.graph.DefaultPropagationGraphBuilder();
            com.nullguard.visualization.model.PropagationGraph propGraph = visualBuilder.build(projectModel, callGraph, riskModels);
            
            com.nullguard.visualization.export.JsonGraphExporter jsonExporter = new com.nullguard.visualization.export.JsonGraphExporter();
            String jsonOutput = jsonExporter.export(propGraph, null);
            
            // Save JSON with timestamp
            String jsonFilename = "nullguard-report-" + timestamp + ".json";
            java.io.File reportFile = new java.io.File(targetDir, jsonFilename);
            java.nio.file.Files.writeString(reportFile.toPath(), jsonOutput);
            
            // Save latest JSON link (can be used by external dashboards)
            java.io.File latestReportFile = new java.io.File(targetDir, "nullguard-report-latest.json");
            java.nio.file.Files.writeString(latestReportFile.toPath(), jsonOutput);
            
            // Generate a simple HTML Dashboard
            String htmlDashboard = "<!DOCTYPE html>\n" +
                "<html><head><title>NullGuard Dashboard</title>\n" +
                "<style>\n" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 2rem; }\n" +
                ".container { max-width: 1200px; margin: 0 auto; }\n" +
                "h1 { color: #38bdf8; }\n" +
                ".card { background: #1e293b; padding: 1.5rem; border-radius: 8px; margin-bottom: 1rem; border: 1px solid #334155; }\n" +
                ".stat { font-size: 2rem; font-weight: bold; color: #10b981; }\n" +
                "</style></head><body>\n" +
                "<div class='container'>\n" +
                "<h1>NullGuard Stability Dashboard</h1>\n" +
                "<p>Project: " + project.getName() + " | Run Time: " + timestamp + "</p>\n" +
                "<div class='card'><h2>Risk Models Analyzed</h2>\n" +
                "<div class='stat'>" + riskModels.size() + " Methods</div></div>\n" +
                "<div class='card'><h2>Raw JSON Output</h2>\n" +
                "<pre style='color: #a5b4fc; overflow-x: auto;'>" + jsonOutput + "</pre>\n" +
                "</div></div></body></html>";
                
            java.io.File htmlFile = new java.io.File(targetDir, "nullguard-dashboard-" + timestamp + ".html");
            java.nio.file.Files.writeString(htmlFile.toPath(), htmlDashboard);
            java.io.File latestHtmlFile = new java.io.File(targetDir, "nullguard-dashboard-latest.html");
            java.nio.file.Files.writeString(latestHtmlFile.toPath(), htmlDashboard);
            
            getLog().info("Analysis report successfully written to: " + reportFile.getAbsolutePath());
            getLog().info("View Dashboard at: " + latestHtmlFile.getAbsolutePath());
            
        } catch (Exception e) {
            getLog().error("NullGuard analysis failed", e);
            throw new MojoExecutionException("Error during NullGuard analysis", e);
        }

        getLog().info("NullGuard analysis completed successfully.");
    }
}
