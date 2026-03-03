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
            
            // 1.5 Run Analysis Pipeline
            getLog().info("Running Analysis Pipeline...");
            com.nullguard.analysis.config.AnalysisConfig analysisConfig = new com.nullguard.analysis.config.AnalysisConfig(
                10, 2.0, 0.3, 0.7, 5
            );
            com.nullguard.analysis.orchestrator.AnalysisOrchestrator orchestrator = new com.nullguard.analysis.orchestrator.AnalysisOrchestrator(analysisConfig);
            orchestrator.analyze(projectModel);
            
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
            com.nullguard.scoring.scoring.DefaultStabilityScorer scorer = new com.nullguard.scoring.scoring.DefaultStabilityScorer();
            com.nullguard.scoring.model.ProjectRiskSummary projectSummary = scorer.score(riskModels, callGraph, config);
            
            // Generate timestamp for history tracking
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = sdf.format(new java.util.Date());
            
            com.nullguard.visualization.graph.DefaultPropagationGraphBuilder visualBuilder = new com.nullguard.visualization.graph.DefaultPropagationGraphBuilder();
            com.nullguard.visualization.model.PropagationGraph propGraph = visualBuilder.build(projectModel, callGraph, riskModels);
            
            com.nullguard.visualization.export.JsonGraphExporter jsonExporter = new com.nullguard.visualization.export.JsonGraphExporter();
            String jsonOutput = jsonExporter.export(propGraph, projectSummary);
            
            // Save JSON with timestamp
            String jsonFilename = "nullguard-report-" + timestamp + ".json";
            java.io.File reportFile = new java.io.File(targetDir, jsonFilename);
            java.nio.file.Files.writeString(reportFile.toPath(), jsonOutput);
            
            // Save latest JSON link (can be used by external dashboards)
            java.io.File latestReportFile = new java.io.File(targetDir, "nullguard-report-latest.json");
            java.nio.file.Files.writeString(latestReportFile.toPath(), jsonOutput);
            
            StringBuilder hotspotsHtml = new StringBuilder();
            for (com.nullguard.analysis.model.ArchitecturalHotspot hotspot : orchestrator.getHotspotDetector().getArchitecturalHotspots()) {
                hotspotsHtml.append("<tr>")
                    .append("<td><code style='color:#cbd5e1; font-size:0.9em;'>").append(hotspot.getMethodRef()).append("</code></td>")
                    .append("<td>").append(String.format("%.2f", hotspot.getHotspotScore())).append("</td>")
                    .append("<td><span class='badge badge-").append(hotspot.getSeverity()).append("'>").append(hotspot.getSeverity()).append("</span></td>")
                    .append("</tr>");
            }

            StringBuilder apiHtml = new StringBuilder();
            for (java.util.Map.Entry<String, com.nullguard.analysis.model.ReachData> entry : orchestrator.getApiEndpointAnalyzer().getReachTracker().getReachMap().entrySet()) {
                com.nullguard.analysis.model.ReachData rd = entry.getValue();
                apiHtml.append("<tr>")
                    .append("<td><code style='color:#cbd5e1; font-size:0.9em;'>").append(entry.getKey()).append("</code></td>")
                    .append("<td>").append(rd.getCount()).append("</td>")
                    .append("<td>").append(rd.getReachableApis().size()).append(" APIs reachable</td>")
                    .append("</tr>");
            }

            // Generate an Enhanced HTML Dashboard
            String htmlDashboard = "<!DOCTYPE html>\n" +
                "<html><head><title>NullGuard Dashboard</title>\n" +
                "<style>\n" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 2rem; }\n" +
                ".container { max-width: 1200px; margin: 0 auto; }\n" +
                "h1 { color: #38bdf8; }\n" +
                ".card { background: #1e293b; padding: 1.5rem; border-radius: 8px; margin-bottom: 1rem; border: 1px solid #334155; }\n" +
                ".stat-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 1rem; margin-top: 1rem; }\n" +
                ".stat-box { background: #0f172a; padding: 1rem; border-radius: 6px; border: 1px solid #334155; }\n" +
                ".stat-label { font-size: 0.85em; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.05em; }\n" +
                ".stat-value { font-size: 1.8rem; font-weight: bold; color: #10b981; margin-top: 0.5rem; }\n" +
                ".grade-A { color: #10b981; } .grade-B { color: #3b82f6; } .grade-C { color: #f59e0b; } .grade-D { color: #f97316; } .grade-F { color: #ef4444; }\n" +
                "table { width: 100%; border-collapse: collapse; margin-top: 1rem; }\n" +
                "th, td { text-align: left; padding: 12px; border-bottom: 1px solid #334155; }\n" +
                "th { background-color: #0f172a; color: #cbd5e1; }\n" +
                "tr:hover { background-color: #334155; }\n" +
                ".badge { padding: 4px 8px; border-radius: 4px; font-size: 0.85em; font-weight: bold; }\n" +
                ".badge-CRITICAL { background: #991b1b; color: #fff; }\n" +
                ".badge-HIGH { background: #ef4444; color: #fff; }\n" +
                ".badge-MEDIUM { background: #f59e0b; color: #fff; }\n" +
                ".badge-MODERATE { background: #f59e0b; color: #fff; }\n" +
                ".badge-LOW { background: #10b981; color: #fff; }\n" +
                "</style></head><body>\n" +
                "<div class='container'>\n" +
                "<h1>NullGuard Stability Dashboard</h1>\n" +
                "<p>Project: " + project.getName() + " | Run Time: " + timestamp + "</p>\n" +
                "<div class='card'><h2>Project Risk Summary</h2>\n" +
                "<div class='stat-grid' id='summaryGrid'></div></div>\n" +
                "<div class='card'><h2>API Flow Paths & Reachability Tracker</h2>\n" +
                "<table><thead><tr><th>Endpoint Ref</th><th>Distinct Paths Count</th><th>Reach Metrics</th></tr></thead>\n" +
                "<tbody>" + (apiHtml.length() > 0 ? apiHtml.toString() : "<tr><td colspan='3' style='color:#94a3b8;'>No distinct API paths detected</td></tr>") + "</tbody></table>\n" +
                "</div>\n" +
                "<div class='card'><h2>Architectural Hotspots (API Influence)</h2>\n" +
                "<table><thead><tr><th>Method Ref</th><th>Hotspot Score</th><th>Severity</th></tr></thead>\n" +
                "<tbody>" + (hotspotsHtml.length() > 0 ? hotspotsHtml.toString() : "<tr><td colspan='3' style='color:#94a3b8;'>No hotspots detected</td></tr>") + "</tbody></table>\n" +
                "</div>\n" +
                "<div class='card'><h2>Method Risk Analysis & Propagation details</h2>\n" +
                "<table><thead><tr><th>Method ID</th><th>Intrinsic Risk</th><th>Propagated Risk</th><th>Adjusted Risk</th><th>Risk Level</th><th>External</th></tr></thead>\n" +
                "<tbody id='nodesTable'></tbody></table>\n" +
                "</div>\n" +
                "<div class='card'><h2>Raw JSON Output</h2>\n" +
                "<details><summary style='cursor:pointer; color:#38bdf8;'>View Raw Data</summary>\n" +
                "<pre style='color: #a5b4fc; overflow-x: auto;'>" + jsonOutput.replace("<", "&lt;").replace(">", "&gt;") + "</pre>\n" +
                "</details></div></div>\n" +
                "<script>\n" +
                "const data = " + jsonOutput + ";\n" +
                "const sum = data.summary;\n" +
                "document.getElementById('summaryGrid').innerHTML = `\n" +
                "  <div class='stat-box'><div class='stat-label'>Stability Grade</div><div class='stat-value grade-${sum.grade}'>${sum.grade}</div></div>\n" +
                "  <div class='stat-box'><div class='stat-label'>Stability Index</div><div class='stat-value'>${sum.stabilityIndex.toFixed(2)}</div></div>\n" +
                "  <div class='stat-box'><div class='stat-label'>API Contract / Methods</div><div class='stat-value'>${sum.totalMethods}</div></div>\n" +
                "  <div class='stat-box'><div class='stat-label'>High Risk Methods</div><div class='stat-value' style='color:#ef4444'>${sum.highRiskMethods}</div></div>\n" +
                "  <div class='stat-box'><div class='stat-label'>Max Method Risk</div><div class='stat-value' style='color:#f59e0b'>${sum.maxRisk.toFixed(2)}</div></div>\n" +
                "  <div class='stat-box'><div class='stat-label'>Blast Radius Scope</div><div class='stat-value' style='color:#3b82f6'>${sum.blastRadiusScore.toFixed(2)}</div></div>\n" +
                "`;\n" +
                "const nodes = data.graph.nodes;\n" +
                "const tbody = document.getElementById('nodesTable');\n" +
                "const nodesArray = Object.values(nodes).sort((a,b) => b.adjustedRisk - a.adjustedRisk);\n" +
                "nodesArray.forEach(node => {\n" +
                "  const tr = document.createElement('tr');\n" +
                "  tr.innerHTML = `\n" +
                "    <td><code style='color:#cbd5e1; font-size:0.9em;'>${node.methodId}</code></td>\n" +
                "    <td>${node.intrinsicRisk.toFixed(2)}</td>\n" +
                "    <td>${node.propagatedRisk.toFixed(2)}</td>\n" +
                "    <td>${node.adjustedRisk.toFixed(2)}</td>\n" +
                "    <td><span class='badge badge-${node.riskLevel}'>${node.riskLevel}</span></td>\n" +
                "    <td>${node.external ? 'Yes' : 'No'}</td>\n" +
                "  `;\n" +
                "  tbody.appendChild(tr);\n" +
                "});\n" +
                "</script></body></html>";
                
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
