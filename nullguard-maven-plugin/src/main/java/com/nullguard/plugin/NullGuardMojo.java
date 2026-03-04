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
        // Placeholder rows will be replaced by JS logic for hotspots

        StringBuilder suggestionRows = new StringBuilder();
        for (Suggestion s : result.getSuggestions()) {
            suggestionRows.append("<tr>")
                .append("<td>").append(s.getSuggestionType()).append("</td>")
                .append("<td><code style='color:#cbd5e1;font-size:.9em'>").append(s.getMethodId()).append("</code></td>")
                .append("<td>").append(s.getMessage()).append("</td>")
                .append("<td>").append(String.format("%.2f", s.getFinalScore())).append("</td>")
                .append("</tr>\n");
        }

        String cycleSection = "";
        if (!result.getCycleWarnings().isEmpty()) {
            StringBuilder cwHtml = new StringBuilder();
            cwHtml.append("<div class='warn-panel'><h2>&#9888; Call Graph Cycle Warnings</h2><ul>\n");
            for (String w : result.getCycleWarnings()) {
                cwHtml.append("<li>").append(sanitize(w)).append("</li>\n");
            }
            cwHtml.append("</ul><p class='warn-note'>These cycles are handled safely by the fixpoint "
                    + "propagation engine &ndash; risk scores are still accurate.</p></div>\n");
            cycleSection = cwHtml.toString();
        }

        StringBuilder reasonJson = new StringBuilder("{\n");
        boolean firstEntry = true;
        for (java.util.Map.Entry<String, java.util.List<String>> e : result.getRiskReasonMap().entrySet()) {
            if (!firstEntry) reasonJson.append(",\n");
            firstEntry = false;
            reasonJson.append("  ").append(jsonStr(e.getKey())).append(": [");
            boolean firstR = true;
            for (String r : e.getValue()) {
                if (!firstR) reasonJson.append(", ");
                firstR = false;
                reasonJson.append(jsonStr(r));
            }
            reasonJson.append("]");
        }
        reasonJson.append("\n}");

        StringBuilder apiRows = new StringBuilder();
        for (com.nullguard.analysis.model.ApiEndpointModel ep : result.getApiEndpoints()) {
            String chainHtml = buildChainHtml(ep.getPropagationChain());
            apiRows.append("<tr>")
                .append("<td><span class='badge badge-http-").append(sanitize(ep.getHttpMethod())).append("'>").append(sanitize(ep.getHttpMethod())).append("</span></td>")
                .append("<td><code style='color:#38bdf8'>").append(sanitize(ep.getPath())).append("</code></td>")
                .append("<td><span class=\"badge badge-MODERATE\">").append(String.format("%.2f", ep.getApiRiskScore())).append("</span></td>")
                .append("<td>").append(ep.getPropagationDepth()).append("</td>")
                .append("<td><code style='color:#cbd5e1;font-size:.85em'>").append(sanitize(ep.getEndpointId())).append("</code></td>")
                .append("<td><details><summary style='cursor:pointer;color:#94a3b8'>").append(ep.getPropagationChain().size()).append(" methods</summary>")
                .append("<div class='chain'>").append(chainHtml).append("</div></details></td>")
                .append("</tr>\n");
        }
        String apiSection = apiRows.length() > 0 ? apiRows.toString() : "<tr><td colspan='6' style='text-align:center;padding:2rem'>No APIs detected</td></tr>";

        return "<!DOCTYPE html>\n<html><head><title>NullGuard Dashboard</title>\n" +
            "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n" +
            "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n" +
            "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n" +
            "<style>\n" +
            ":root {\n" +
            "  --bg: #0b0f1a; --card: #151c2e; --border: #242f48; --text-main: #f8fafc;\n" +
            "  --text-muted: #94a3b8; --primary: #38bdf8; --success: #10b981;\n" +
            "  --warning: #f59e0b; --danger: #ef4444;\n" +
            "}\n" +
            "body{font-family:'Inter', sans-serif;background:var(--bg);color:var(--text-main);margin:0;padding:2rem;line-height:1.5}\n" +
            ".container{max-width:1400px;margin:0 auto}\n" +
            "h1{color:var(--primary);font-weight:700;letter-spacing:-0.02em;margin-bottom:0.5rem}\n" +
            ".subtitle{color:var(--text-muted);font-size:0.9rem;margin-bottom:2.5rem}\n" +
            "h2{color:var(--text-muted);font-size:0.85rem;text-transform:uppercase;letter-spacing:.1em;margin:2rem 0 1rem;font-weight:600}\n" +
            ".card{background:var(--card);padding:1.75rem;border-radius:12px;margin-bottom:1.5rem;border:1px solid var(--border);box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1)}\n" +
            ".stat-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:1.25rem}\n" +
            ".stat-box{background:rgba(11,15,26,0.5);padding:1.25rem;border-radius:10px;border:1px solid var(--border);display:flex;flex-direction:column;gap:0.4rem}\n" +
            ".stat-label{font-size:.7em;color:var(--text-muted);text-transform:uppercase;letter-spacing:.05em;font-weight:600}\n" +
            ".stat-value{font-size:1.75rem;font-weight:700;color:var(--success)}\n" +
            ".stat-meta{font-size:0.7rem;color:var(--text-muted)}\n" +
            "table{width:100%;border-collapse:separate;border-spacing:0}\n" +
            "th,td{text-align:left;padding:12px 16px;border-bottom:1px solid var(--border)}\n" +
            "th{background:rgba(15,23,42,0.6);color:#cbd5e1;font-size:0.75rem;text-transform:uppercase;letter-spacing:0.05em}\n" +
            ".badge{padding:4px 8px;border-radius:6px;font-size:.7rem;font-weight:600}\n" +
            ".badge-HIGH{background:rgba(239,68,68,0.15);color:#fca5a5;border:1px solid rgba(239,68,68,0.3)}\n" +
            ".badge-MODERATE{background:rgba(245,158,11,0.15);color:#fcd34d;border:1px solid rgba(245,158,11,0.3)}\n" +
            ".badge-LOW{background:rgba(16,185,129,0.15);color:#6ee7b7;border:1px solid rgba(16,185,129,0.3)}\n" +
            ".badge-hotspot{background:rgba(239,68,68,0.1);color:#ef4444;border:1px solid #ef4444}\n" +
            ".badge-http-POST{background:rgba(16,185,129,0.1);color:#34d399;border:1px solid rgba(16,185,129,0.3)}\n" +
            ".chain{padding:1rem;background:rgba(11,15,26,0.5);border-radius:8px;font-size:.8rem;border:1px solid var(--border)}\n" +
            ".chain-node{padding:2px 0;color:#cbd5e1;display:flex;align-items:center;gap:8px}\n" +
            ".chain-node::before{content:'↳';color:var(--primary)}\n" +
            ".reason-list{margin:8px 0;padding-left:1.5em;color:#94a3b8;font-size:.8rem;list-style-type:none}\n" +
            ".reason-list li::before{content:'•';color:var(--primary);margin-right:8px}\n" +
            ".search-input{width:100%;background:var(--card);border:1px solid var(--border);padding:0.6rem 1rem;border-radius:8px;color:var(--text-main);margin-bottom:1rem}\n" +
            ".impact-chain{margin-top:0.5rem;padding:0.5rem;background:rgba(239,68,68,0.05);border-radius:4px;border-left:2px solid #ef4444;font-size:0.75rem}\n" +
            ".impact-chain summary{color:#fca5a5;font-weight:600;margin-bottom:0.25rem}\n" +
            ".impact-chain ul{margin:0;padding-left:1.2rem;list-style-type:none;color:#94a3b8}\n" +
            ".impact-chain li{position:relative;margin-bottom:2px}\n" +
            ".impact-chain li::before{content:'\u2191';position:absolute;left:-1rem;color:#ef4444}\n" +
            "</style></head><body>\n" +
            "<div class='container'>\n" +
            "<h1>NullGuard Stability Dashboard</h1>\n" +
            "<p class='subtitle'>Project: " + project.getName() + " | Analysed: " + timestamp + "</p>\n" +
            cycleSection +
            "<div class='card'><h2>Risk Summary</h2><div class='stat-grid' id='summaryGrid'></div></div>\n" +
            "<div class='card'><h2>API Flow Analysis (v1.1)</h2>\n" +
            "<table><thead><tr><th>HTTP</th><th>Path</th><th>Risk</th><th>Depth</th><th>Entry</th><th>Propagation</th></tr></thead>\n" +
            "<tbody>" + apiSection + "</tbody></table></div>\n" +
            "<div class='card'><h2>Architectural Hotspots</h2>\n" +
            "<table><thead><tr><th>Method</th><th>Total Impacted APIs</th><th>Risk</th><th>Status</th></tr></thead>\n" +
            "<tbody id='hotspotTable'><tr><td colspan='4' style='text-align:center;padding:2rem;color:var(--text-muted)'>Calculating hotspots...</td></tr></tbody></table></div>\n" +
            "<div class='card'><h2>Risk Analysis</h2>\n" +
            "<input type='text' id='methodSearch' class='search-input' placeholder='Search methods...'>\n" +
            "<table><thead><tr><th>Method</th><th>Intrinsic</th><th>Propagated</th><th>Adjusted</th><th>Level</th><th>Blast Radius (APIs)</th><th>Factors & Impact Map</th></tr></thead>\n" +
            "<tbody id='riskTable'></tbody></table></div>\n" +
            "<div class='card'><h2>Raw JSON</h2><details><summary style='cursor:pointer;color:var(--primary)'>View Data</summary>\n" +
            "<pre style='color:var(--text-muted);font-size:0.75rem;max-height:400px;overflow:auto'>" + jsonOutput.replace("<", "&lt;") + "</pre></details></div>\n" +
            "</div><script>\n" +
            "const data = " + jsonOutput + ";\n" +
            "const reasons = " + reasonJson.toString() + ";\n" +
            "const s = data.summary; const nodesMap = data.graph.nodes; const edges = data.graph.edges;\n" +
            "const inDegree = {}; edges.forEach(e => inDegree[e.to] = (inDegree[e.to] || 0) + 1);\n" +
            "const hs = Object.values(nodesMap).filter(n => inDegree[n.methodId] > 3 || (n.adjustedRisk > 30 && inDegree[n.methodId] > 1)).sort((a,b) => (inDegree[b.methodId]||0)-(inDegree[a.methodId]||0));\n" +
            "document.getElementById('summaryGrid').innerHTML = `\n" +
            "  <div class='stat-box'><div class='stat-label'>Grade</div><div class='stat-value grade-${s.grade}'>${s.grade}</div><div class='stat-meta'>Index: ${s.stabilityIndex.toFixed(2)}</div></div>\n" +
            "  <div class='stat-box'><div class='stat-label'>Methods</div><div class='stat-value'>${s.totalMethods}</div><div class='stat-meta'>${s.totalExternalMethods} ext</div></div>\n" +
            "  <div class='stat-box'><div class='stat-label'>Risk</div><div class='stat-value' style='color:var(--danger)'>${s.highRiskMethods}</div><div class='stat-meta'>High-risk ratio: ${(s.highRiskRatio || 0).toFixed(1)}%</div></div>\n" +
            "  <div class='stat-box'><div class='stat-label'>Blast Radius</div><div class='stat-value' style='color:var(--primary)'>${s.blastRadiusScore.toFixed(2)}</div><div class='stat-meta'>Max: ${s.maxRisk.toFixed(2)}</div></div>\n" +
            "  <div class='stat-box'><div class='stat-label'>Violations</div><div class='stat-value'>${s.contractViolations || 0}</div><div class='stat-meta'>Contract alignment</div></div>`;\n" +
            "const hst = document.getElementById('hotspotTable'); hst.innerHTML = hs.length ? '' : '<tr><td colspan=4 style=\"text-align:center\">No hotspots</td></tr>';\n" +
            "hs.slice(0,5).forEach(h => {\n" +
            "  const tr = document.createElement('tr'); \n" +
            "  const impactCount = h.impactMap ? h.impactMap.length : 0;\n" +
            "  tr.innerHTML = `<td><code>${h.methodId.split('.').pop()}</code></td><td><span class='badge badge-hotspot'>${impactCount} APIs Affected</span></td><td>${h.adjustedRisk.toFixed(2)}</td><td><span class='badge badge-hotspot'>HOTSPOT</span></td>`;\n" +
            "  hst.appendChild(tr);\n" +
            "});\n" +
            "const allNodes = Object.values(nodesMap).sort((a,b) => b.adjustedRisk - a.adjustedRisk);\n" +
            "function render(q='') {\n" +
            "  const tb = document.getElementById('riskTable'); tb.innerHTML = '';\n" +
            "  allNodes.filter(n => n.methodId.toLowerCase().includes(q.toLowerCase())).forEach(n => {\n" +
            "    const rs = reasons[n.methodId] || []; const tr = document.createElement('tr');\n" +
            "    const ic = n.impactMap || []; \n" +
            "    const impactHtml = ic.length ? `<div class='impact-chain'><details><summary>\u26A0 Blast Radius: Failure breaks ${ic.length} APIs</summary><ul>${ic.map(c => `<li><strong>${c.severity}</strong>: Entry Point: <code>${c.entryPoint.split('.').pop()}</code></li>`).join('')}</ul></details></div>` : '';\n" +
            "    tr.innerHTML = `<td><code style='font-size:0.75rem'>${n.methodId}</code></td><td>${n.intrinsicRisk.toFixed(2)}</td><td>${n.propagatedRisk.toFixed(2)}</td><td><strong>${n.adjustedRisk.toFixed(2)}</strong></td><td><span class='badge badge-${n.riskLevel}'>${n.riskLevel}</span></td><td><span class='badge badge-hotspot'>${ic.length} Impacts</span></td><td><details><summary style='cursor:pointer;color:var(--text-muted)'>${rs.length} factors</summary><ul class='reason-list'>${rs.map(r=>`<li>${r}</li>`).join('')}</ul></details>${impactHtml}</td>`;\n" +
            "    tb.appendChild(tr);\n" +
            "  });\n" +
            "}\n" +
            "document.getElementById('methodSearch').addEventListener('input', e => render(e.target.value));\n" +
            "render();\n" +
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

    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "")
                       .replace("←", "<-")    // safe ASCII for JSON
               + "\"";
    }
}
