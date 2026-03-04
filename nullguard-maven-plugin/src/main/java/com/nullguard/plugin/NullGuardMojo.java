package com.nullguard.plugin;

import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.bootstrap.EngineBootstrap;
import com.nullguard.bootstrap.FinalAnalysisResult;
import com.nullguard.bootstrap.NullGuardConfig;
import com.nullguard.suggestions.model.Suggestion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * NullGuardMojo – Maven plugin entry point for the NullGuard Stability Intelligence Engine.
 *
 * <p><strong>Bound phase:</strong> {@code verify}
 * <p><strong>Goal:</strong> {@code analyze}
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Bind to the {@code verify} lifecycle phase</li>
 *   <li>Load project source path from Maven project model</li>
 *   <li>Build a {@link NullGuardConfig} from plugin parameters</li>
 *   <li>Delegate to {@link EngineBootstrap} – NO pipeline logic here</li>
 *   <li>Write HTML dashboard + JSON report to {@code target/nullguard/}</li>
 *   <li>Apply hotspot fail policy and exit with correct Maven failure code</li>
 * </ul>
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY)
public class NullGuardMojo extends AbstractMojo {

    // ── Maven-injected parameters ─────────────────────────────────────────────

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /** Hotspot severity level at which the build fails (CRITICAL, HIGH, MODERATE, LOW). */
    @Parameter(property = "nullguard.failThreshold", defaultValue = "CRITICAL")
    private String failThreshold;

    /** Whether to fail the build when the threshold is breached. */
    @Parameter(property = "nullguard.failBuild", defaultValue = "false")
    private boolean failBuild;

    /** Scoring: decay factor for risk propagation (0–1). */
    @Parameter(property = "nullguard.scoringDecayFactor", defaultValue = "0.85")
    private double scoringDecayFactor;

    /** Scoring: penalty multiplier for external method calls. */
    @Parameter(property = "nullguard.externalPenaltyMultiplier", defaultValue = "1.2")
    private double externalPenaltyMultiplier;

    /** Scoring: convergence threshold for fixpoint iteration. */
    @Parameter(property = "nullguard.convergenceThreshold", defaultValue = "0.001")
    private double convergenceThreshold;

    /** Scoring: maximum fixpoint iterations. */
    @Parameter(property = "nullguard.maxScoringIterations", defaultValue = "100")
    private int maxScoringIterations;

    /** Scoring: risk score at or above which a method is classified as high-risk. */
    @Parameter(property = "nullguard.highRiskThreshold", defaultValue = "70")
    private int highRiskThreshold;

    // ── Mojo entry point ──────────────────────────────────────────────────────

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        getLog().info("-------------------------------------------------------");
        getLog().info(" NullGuard: Stability Intelligence Engine (v1.0)");
        getLog().info("-------------------------------------------------------");
        getLog().info("Analyzing project : " + project.getName());
        getLog().info("Source directory  : " + project.getBuild().getSourceDirectory());

        Path sourcePath = Paths.get(project.getBuild().getSourceDirectory());
        if (!Files.exists(sourcePath)) {
            getLog().info("Source directory does not exist – skipping analysis: " + sourcePath);
            return;
        }

        try {
            // ── 1. Build configuration ──────────────────────────────────────
            Path outputDir = Paths.get(project.getBuild().getDirectory(), "nullguard");
            NullGuardConfig config = NullGuardConfig.defaults()
                    .failBuild(failBuild)
                    .failThreshold(failThreshold)
                    .scoringDecayFactor(scoringDecayFactor)
                    .externalPenaltyMultiplier(externalPenaltyMultiplier)
                    .convergenceThreshold(convergenceThreshold)
                    .maxScoringIterations(maxScoringIterations)
                    .highRiskThreshold(highRiskThreshold)
                    .outputDirectory(outputDir)
                    .build();

            // ── 2. Run pipeline via bootstrap ───────────────────────────────
            getLog().info("Initialising NullGuard engine...");
            EngineBootstrap bootstrap = new EngineBootstrap(config);

            getLog().info("Running analysis pipeline...");
            FinalAnalysisResult result = bootstrap.run(sourcePath);

            // ── 3. Write outputs ────────────────────────────────────────────
            writeOutputs(result, outputDir);

            // ── 4. Print concise summary ────────────────────────────────────
            printSummary(result);

            // ── 5. Apply hotspot fail policy ────────────────────────────────
            applyFailPolicy(result);

        } catch (Exception e) {
            getLog().error("NullGuard analysis failed", e);
            throw new MojoExecutionException("NullGuard analysis failed: " + e.getMessage(), e);
        }

        getLog().info("NullGuard analysis completed successfully.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void writeOutputs(FinalAnalysisResult result, Path outputDir) throws Exception {
        File dir = outputDir.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String jsonContent = result.getVisualizations().getJsonGraph();
        String dotContent  = result.getVisualizations().getDotGraph();

        // ── JSON ──────────────────────────────────────────────────────────────
        Path jsonFile = outputDir.resolve("nullguard-report-" + timestamp + ".json");
        Path jsonLatest = outputDir.resolve("nullguard-report-latest.json");
        Files.writeString(jsonFile, jsonContent);
        Files.writeString(jsonLatest, jsonContent);

        // ── DOT ───────────────────────────────────────────────────────────────
        Path dotFile  = outputDir.resolve("nullguard-graph-" + timestamp + ".dot");
        Path dotLatest = outputDir.resolve("nullguard-graph-latest.dot");
        Files.writeString(dotFile, dotContent);
        Files.writeString(dotLatest, dotContent);

        // ── HTML Dashboard ────────────────────────────────────────────────────
        String htmlContent = buildHtmlDashboard(result, timestamp, jsonContent);
        Path htmlFile   = outputDir.resolve("nullguard-dashboard-" + timestamp + ".html");
        Path htmlLatest = outputDir.resolve("nullguard-dashboard-latest.html");
        Files.writeString(htmlFile, htmlContent);
        Files.writeString(htmlLatest, htmlContent);

        getLog().info("JSON report  → " + jsonLatest.toAbsolutePath());
        getLog().info("HTML dashboard → " + htmlLatest.toAbsolutePath());
    }

    private void printSummary(FinalAnalysisResult result) {
        var summary = result.getRiskSummary();
        getLog().info("┌─────────────────────────────────────────┐");
        getLog().info("│  NullGuard Analysis Summary              │");
        getLog().info("├─────────────────────────────────────────┤");
        getLog().info(String.format("│  Grade           : %-20s │", summary.getGrade()));
        getLog().info(String.format("│  Stability Index : %-20.2f │", summary.getStabilityIndex()));
        getLog().info(String.format("│  Total Methods   : %-20d │", summary.getTotalMethods()));
        getLog().info(String.format("│  High Risk Meth. : %-20d │", summary.getHighRiskMethods()));
        getLog().info(String.format("│  Hotspots        : %-20d │", result.getHotspots().size()));
        getLog().info(String.format("│  Suggestions     : %-20d │", result.getSuggestions().size()));
        getLog().info(String.format("│  Pipeline time   : %-18dms │", result.getTiming().getTotalPipelineDurationMs()));
        getLog().info("└─────────────────────────────────────────┘");

        if (!result.getHotspots().isEmpty()) {
            getLog().info("Top Architectural Hotspots:");
            result.getHotspots().stream().limit(5).forEach(h ->
                getLog().info(String.format("  [%s] %s (score=%.2f)",
                        h.getSeverity(), h.getMethodRef(), h.getHotspotScore())));
        }

        if (!result.getSuggestions().isEmpty()) {
            getLog().info("Top Suggestions:");
            result.getSuggestions().stream().limit(3).forEach(s ->
                getLog().info("  → " + s.getMessage()));
        }
    }

    /**
     * Fails the Maven build if {@code failBuild=true} AND the result contains
     * at least one hotspot at or above the configured {@code failThreshold} severity.
     *
     * <p>Exit rules:
     * <ul>
     *   <li>hotspot severity ≥ failThreshold AND failBuild=true → {@code MojoFailureException} (exit 1)</li>
     *   <li>otherwise → normal completion (exit 0)</li>
     * </ul>
     */
    private void applyFailPolicy(FinalAnalysisResult result) throws MojoFailureException {
        if (!failBuild) {
            return;
        }
        if (result.hasHotspotsAtOrAboveSeverity(failThreshold)) {
            String msg = String.format(
                    "NullGuard: build failed – hotspot(s) at or above severity '%s' detected. " +
                    "Total hotspots: %d. Set nullguard.failBuild=false to suppress.",
                    failThreshold, result.getHotspots().size());
            getLog().error(msg);
            throw new MojoFailureException(msg);
        }
    }

    // ── HTML Dashboard ────────────────────────────────────────────────────────

    private String buildHtmlDashboard(FinalAnalysisResult result, String timestamp, String jsonOutput) {
        StringBuilder hotspotRows = new StringBuilder();
        for (ArchitecturalHotspot h : result.getHotspots()) {
            hotspotRows.append("<tr>")
                .append("<td><code style='color:#cbd5e1;font-size:.9em'>").append(h.getMethodRef()).append("</code></td>")
                .append("<td>").append(String.format("%.2f", h.getHotspotScore())).append("</td>")
                .append("<td><span class='badge badge-").append(h.getSeverity()).append("'>").append(h.getSeverity()).append("</span></td>")
                .append("</tr>\n");
        }

        StringBuilder suggestionRows = new StringBuilder();
        for (Suggestion s : result.getSuggestions()) {
            suggestionRows.append("<tr>")
                .append("<td>").append(s.getSuggestionType()).append("</td>")
                .append("<td><code style='color:#cbd5e1;font-size:.9em'>").append(s.getMethodId()).append("</code></td>")
                .append("<td>").append(s.getMessage()).append("</td>")
                .append("<td>").append(String.format("%.2f", s.getFinalScore())).append("</td>")
                .append("</tr>\n");
        }

        // ── API Endpoint rows ─────────────────────────────────────────────────
        StringBuilder apiRows = new StringBuilder();
        for (com.nullguard.analysis.model.ApiEndpointModel ep : result.getApiEndpoints()) {
            String chainHtml = buildChainHtml(ep.getPropagationChain());
            apiRows.append("<tr>")
                .append("<td><span class='badge badge-http-").append(sanitize(ep.getHttpMethod())).append("'>").append(sanitize(ep.getHttpMethod())).append("</span></td>")
                .append("<td><code style='color:#38bdf8'>").append(sanitize(ep.getPath())).append("</code></td>")
                .append("<td><code style='color:#cbd5e1;font-size:.85em'>").append(sanitize(ep.getEndpointId())).append("</code></td>")
                .append("<td><details><summary style='cursor:pointer;color:#94a3b8'>").append(ep.getPropagationChain().size()).append(" methods</summary>")
                .append("<div class='chain'>").append(chainHtml).append("</div></details></td>")
                .append("</tr>\n");
        }
        String apiSection = apiRows.length() > 0
            ? apiRows.toString()
            : "<tr><td colspan='4' style='color:#94a3b8'>No API endpoints detected – "
              + "ensure source has Controller classes or REST verb-named methods</td></tr>";

        return "<!DOCTYPE html>\n<html><head><title>NullGuard Dashboard</title>\n" +
            "<style>\n" +
            "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0f172a;color:#f8fafc;margin:0;padding:2rem}\n" +
            ".container{max-width:1300px;margin:0 auto}\n" +
            "h1{color:#38bdf8}h2{color:#94a3b8;font-size:1rem;text-transform:uppercase;letter-spacing:.05em}\n" +
            ".card{background:#1e293b;padding:1.5rem;border-radius:8px;margin-bottom:1rem;border:1px solid #334155}\n" +
            ".stat-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:1rem;margin-top:1rem}\n" +
            ".stat-box{background:#0f172a;padding:1rem;border-radius:6px;border:1px solid #334155}\n" +
            ".stat-label{font-size:.85em;color:#94a3b8;text-transform:uppercase;letter-spacing:.05em}\n" +
            ".stat-value{font-size:1.8rem;font-weight:bold;color:#10b981;margin-top:.5rem}\n" +
            ".grade-A{color:#10b981}.grade-B{color:#3b82f6}.grade-C{color:#f59e0b}.grade-D{color:#f97316}.grade-F{color:#ef4444}\n" +
            "table{width:100%;border-collapse:collapse;margin-top:1rem}\n" +
            "th,td{text-align:left;padding:12px;border-bottom:1px solid #334155}\n" +
            "th{background:#0f172a;color:#cbd5e1}tr:hover{background:#334155}\n" +
            ".badge{padding:4px 8px;border-radius:4px;font-size:.85em;font-weight:bold}\n" +
            ".badge-CRITICAL{background:#991b1b;color:#fff}.badge-HIGH{background:#ef4444;color:#fff}\n" +
            ".badge-MODERATE{background:#f59e0b;color:#fff}.badge-LOW{background:#10b981;color:#fff}\n" +
            ".badge-http-GET{background:#3b82f6;color:#fff}.badge-http-POST{background:#10b981;color:#fff}\n" +
            ".badge-http-PUT{background:#f59e0b;color:#000}.badge-http-DELETE{background:#ef4444;color:#fff}\n" +
            ".badge-http-PATCH{background:#8b5cf6;color:#fff}.badge-http-UNKNOWN{background:#6b7280;color:#fff}\n" +
            ".chain{margin-top:.5rem;padding:.5rem;background:#0f172a;border-radius:4px;font-size:.8em}\n" +
            ".chain-node{padding:2px 0;color:#a5b4fc}\n" +
            ".chain-node::before{content:'↳ ';color:#38bdf8}\n" +
            ".chain-node:first-child::before{content:'⬡ ';color:#10b981}\n" +
            "</style></head><body>\n" +
            "<div class='container'>\n" +
            "<h1>NullGuard Stability Dashboard</h1>\n" +
            "<p>Project: " + project.getName() + " &nbsp;|&nbsp; Analysed: " + timestamp + "</p>\n" +
            "<div class='card'><h2>Project Risk Summary</h2>\n" +
            "<div class='stat-grid' id='summaryGrid'></div></div>\n" +
            "<div class='card'><h2>API Endpoints &amp; Propagation Chains</h2>\n" +
            "<table><thead><tr><th>HTTP</th><th>Path</th><th>Entry Method</th><th>Propagation Chain</th></tr></thead>\n" +
            "<tbody>" + apiSection + "</tbody></table></div>\n" +
            "<div class='card'><h2>Architectural Hotspots</h2>\n" +
            "<table><thead><tr><th>Method Ref</th><th>Hotspot Score</th><th>Severity</th></tr></thead>\n" +
            "<tbody>" + (hotspotRows.length() > 0 ? hotspotRows
                : "<tr><td colspan='3' style='color:#94a3b8'>No hotspots detected</td></tr>") + "</tbody></table></div>\n" +
            "<div class='card'><h2>Suggestions</h2>\n" +
            "<table><thead><tr><th>Type</th><th>Method</th><th>Description</th><th>Priority Score</th></tr></thead>\n" +
            "<tbody>" + (suggestionRows.length() > 0 ? suggestionRows
                : "<tr><td colspan='4' style='color:#94a3b8'>No suggestions generated</td></tr>") + "</tbody></table></div>\n" +
            "<div class='card'><h2>Method Risk Table</h2>\n" +
            "<table><thead><tr><th>Method ID</th><th>Intrinsic</th><th>Propagated</th><th>Adjusted</th><th>Level</th><th>External</th></tr></thead>\n" +
            "<tbody id='riskTable'></tbody></table></div>\n" +
            "<div class='card'><h2>Raw JSON</h2>\n" +
            "<details><summary style='cursor:pointer;color:#38bdf8'>View Raw Data</summary>\n" +
            "<pre style='color:#a5b4fc;overflow-x:auto'>" + jsonOutput.replace("<", "&lt;").replace(">", "&gt;") + "</pre>\n" +
            "</details></div></div>\n" +
            "<script>\n" +
            "const data=" + jsonOutput + ";\n" +
            "const s=data.summary;\n" +
            "document.getElementById('summaryGrid').innerHTML=`\n" +
            "<div class='stat-box'><div class='stat-label'>Grade</div><div class='stat-value grade-${s.grade}'>${s.grade}</div></div>\n" +
            "<div class='stat-box'><div class='stat-label'>Stability Index</div><div class='stat-value'>${s.stabilityIndex.toFixed(2)}</div></div>\n" +
            "<div class='stat-box'><div class='stat-label'>Total Methods</div><div class='stat-value'>${s.totalMethods}</div></div>\n" +
            "<div class='stat-box'><div class='stat-label'>High Risk Methods</div><div class='stat-value' style='color:#ef4444'>${s.highRiskMethods}</div></div>\n" +
            "<div class='stat-box'><div class='stat-label'>Max Risk</div><div class='stat-value' style='color:#f59e0b'>${s.maxRisk.toFixed(2)}</div></div>\n" +
            "<div class='stat-box'><div class='stat-label'>Blast Radius</div><div class='stat-value' style='color:#3b82f6'>${s.blastRadiusScore.toFixed(2)}</div></div>\n" +
            "`;\n" +
            "const nodes=Object.values(data.graph.nodes).sort((a,b)=>b.adjustedRisk-a.adjustedRisk);\n" +
            "const tb=document.getElementById('riskTable');\n" +
            "nodes.forEach(n=>{\n" +
            "  const tr=document.createElement('tr');\n" +
            "  tr.innerHTML=`<td><code style='color:#cbd5e1;font-size:.9em'>${n.methodId}</code></td>" +
            "<td>${n.intrinsicRisk.toFixed(2)}</td><td>${n.propagatedRisk.toFixed(2)}</td>" +
            "<td>${n.adjustedRisk.toFixed(2)}</td>" +
            "<td><span class='badge badge-${n.riskLevel}'>${n.riskLevel}</span></td>" +
            "<td>${n.external?'Yes':'No'}</td>`;\n" +
            "  tb.appendChild(tr);\n" +
            "});\n" +
            "</script></body></html>";
    }

    private String buildChainHtml(java.util.List<String> chain) {
        StringBuilder sb = new StringBuilder();
        for (String node : chain) {
            sb.append("<div class='chain-node'>").append(sanitize(node)).append("</div>\n");
        }
        return sb.toString();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
