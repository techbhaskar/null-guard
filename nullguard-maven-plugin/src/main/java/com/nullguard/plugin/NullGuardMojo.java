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

    /**
     * Scoring: risk score at or above which a method is classified as high-risk.
     *
     * <p>Default is 60 to match {@code RiskLevel.HIGH}'s lower bound. It was 70, which
     * contradicted the band used to colour the graph.
     */
    @Parameter(property = "nullguard.highRiskThreshold", defaultValue = "60")
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
        if (!summary.isAvailable()) {
            // Nothing was scored. Warn instead of reporting a grade, so a broken analysis
            // cannot be mistaken for a passing build.
            getLog().warn("NullGuard produced no data: no methods were scored. This usually means "
                        + "sources failed to parse or no CFGs were built - it does NOT mean the "
                        + "project is risk-free.");
            getLog().info("└─────────────────────────────────────────┘");
            return;
        }
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

        // The JSON graph export intentionally carries only {summary, graph}. The dashboard
        // also renders suggestions, API endpoints and hotspots, so those collections are
        // serialised separately here and merged into the client-side `data` object.
        // Without this the browser hits `data.suggestions.map(...)` on undefined, which
        // aborts the whole script and silently blanks every section below it.
        String apiJson      = toJson(result.getApiEndpoints());
        String suggestJson  = toJson(result.getSuggestions());
        String hotspotJson  = toJson(result.getHotspots());

        String cycleSection = "";
        if (!result.getCycleWarnings().isEmpty()) {
            StringBuilder cwHtml = new StringBuilder();
            cwHtml.append("<div class='warn-panel'><h3>Call Graph Cycle Warnings</h3><ul>\n");
            for (String w : result.getCycleWarnings()) cwHtml.append("<li>").append(sanitize(w)).append("</li>\n");
            cwHtml.append("</ul></div>\n");
            cycleSection = cwHtml.toString();
        }

        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><title>NullGuard Stability Intelligence</title>\n" +
            "<link rel='preconnect' href='https://fonts.googleapis.com'><link rel='preconnect' href='https://fonts.gstatic.com' crossorigin>\n" +
            "<link href='https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap' rel='stylesheet'>\n" +
            "<style>\n" +
            ":root { \n" +
            "  --bg: #09090b; --sidebar: #09090b; --card: #09090b; --border: #27272a; \n" +
            "  --text-main: #fafafa; --text-muted: #a1a1aa; --primary: #3b82f6; \n" +
            "  --success: #10b981; --warning: #f59e0b; --danger: #ef4444; --accent: #1d4ed8;\n" +
            "}\n" +
            "* { box-sizing: border-box; transition: all 0.2s ease; }\n" +
            "body { font-family:'Inter', system-ui, sans-serif; background:var(--bg); color:var(--text-main); margin:0; display:flex; height:100vh; overflow:hidden; }\n" +
            "\n" +
            "/* Sidebar */\n" +
            ".sidebar { width: 280px; border-right: 1px solid var(--border); display: flex; flex-direction: column; padding: 1.5rem; gap: 2rem; }\n" +
            ".brand { display: flex; align-items: center; gap: 0.75rem; font-weight: 700; font-size: 1.25rem; color: var(--primary); }\n" +
            ".nav { display: flex; flex-direction: column; gap: 0.5rem; }\n" +
            ".nav-item { padding: 0.75rem 1rem; border-radius: 8px; color: var(--text-muted); text-decoration: none; font-size: 0.9rem; font-weight: 500; cursor: pointer; }\n" +
            ".nav-item:hover { background: rgba(255,255,255,0.05); color: var(--text-main); }\n" +
            ".nav-item.active { background: var(--accent); color: white; }\n" +
            "\n" +
            "/* Main Content */\n" +
            ".main { flex: 1; overflow-y: auto; display: flex; flex-direction: column; }\n" +
            ".header { padding: 1.5rem 2rem; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; sticky; top:0; background: rgba(9,9,11,0.8); backdrop-filter: blur(12px); z-index: 10; }\n" +
            ".content { padding: 2rem; max-width: 1400px; margin: 0 auto; width: 100%; display: none; }\n" +
            ".content.active { display: block; animation: fadeIn 0.4s ease; }\n" +
            "\n" +
            "/* Cards & Layout */\n" +
            ".card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 1.5rem; margin-bottom: 1.5rem; }\n" +
            ".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 1.5rem; margin-bottom: 2rem; }\n" +
            ".stat-box { border: 1px solid var(--border); padding: 1.5rem; border-radius: 12px; display: flex; flex-direction: column; gap: 0.5rem; }\n" +
            ".stat-label { font-size: 0.75rem; color: var(--text-muted); text-transform: uppercase; font-weight: 600; }\n" +
            ".stat-value { font-size: 2rem; font-weight: 700; }\n" +
            "\n" +
            "/* Tables */\n" +
            "table { width: 100%; border-collapse: collapse; margin-top: 1rem; }\n" +
            "th { text-align: left; font-size: 0.75rem; text-transform: uppercase; color: var(--text-muted); padding: 1rem; border-bottom: 1px solid var(--border); }\n" +
            "td { padding: 1rem; border-bottom: 1px solid var(--border); font-size: 0.9rem; vertical-align: top; }\n" +
            "tr:hover td { background: rgba(255,255,255,0.02); }\n" +
            "\n" +
            "/* Badges & Monospace */\n" +
            "code, pre { font-family: 'JetBrains Mono', monospace; font-size: 0.85rem; }\n" +
            ".badge { padding: 4px 10px; border-radius: 99px; font-size: 0.7rem; font-weight: 600; white-space: nowrap; }\n" +
            ".badge-CRITICAL { background: rgba(239,68,68,0.2); color: #fca5a5; }\n" +
            ".badge-HIGH { background: rgba(245,158,11,0.2); color: #fcd34d; }\n" +
            ".badge-MEDIUM, .badge-MODERATE { background: rgba(59,130,246,0.2); color: #93c5fd; }\n" +
            ".badge-LOW { background: rgba(16,185,129,0.2); color: #6ee7b7; }\n" +
            "progress { width: 100%; height: 6px; border-radius: 3px; appearance: none; }\n" +
            "progress::-webkit-progress-bar { background: var(--border); border-radius: 3px; }\n" +
            "progress::-webkit-progress-value { background: var(--primary); border-radius: 3px; }\n" +
            "\n" +
            "/* Specialized Components */\n" +
            ".impact-box { margin-top: 0.5rem; padding: 0.75rem; background: #18181b; border: 1px solid var(--border); border-radius: 8px; }\n" +
            ".impact-header { color: var(--danger); font-size: 0.75rem; margin-bottom: 0.5rem; display: flex; align-items: center; gap: 0.5rem; font-weight: 600; }\n" +
            ".impact-list { margin: 0; padding-left: 1.25rem; list-style: none; }\n" +
            ".impact-item { position: relative; margin-bottom: 4px; color: var(--text-muted); }\n" +
            ".impact-item::before { content: '→'; position: absolute; left: -1.25rem; color: var(--danger); }\n" +
            ".search-bar { background: #18181b; border: 1px solid var(--border); padding: 0.75rem 1.25rem; border-radius: 10px; color: white; width: 400px; }\n" +
            ".warn-panel { background: rgba(239,68,68,0.05); border: 1px solid var(--danger); padding: 1.5rem; border-radius: 12px; margin-bottom: 2rem; }\n" +
            "\n" +
            "@keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }\n" +
            "</style></head><body>\n" +
            "\n" +
            "<aside class='sidebar'>\n" +
            "  <div class='brand'><div style='width:32px;height:32px;background:var(--primary);border-radius:8px;'></div> NullGuard Intelligence</div>\n" +
            "  <nav class='nav'>\n" +
            "    <a onclick=\"show('overview')\" class='nav-item active' id='nav-overview'>Overview</a>\n" +
            "    <a onclick=\"show('apis')\" class='nav-item' id='nav-apis'>API Flow Mapping</a>\n" +
            "    <a onclick=\"show('hotspots')\" class='nav-item' id='nav-hotspots'>Architectural Hotspots</a>\n" +
            "    <a onclick=\"show('explorer')\" class='nav-item' id='nav-explorer'>Method Explorer</a>\n" +
            "    <a onclick=\"show('raw')\" class='nav-item' id='nav-raw'>JSON Telemetry</a>\n" +
            "  </nav>\n" +
            "  <div style='margin-top:auto; font-size:0.75rem; color:var(--text-muted);'>\n" +
            "    Analysis: " + timestamp + "<br>Project: " + sanitize(project.getName()) + "\n" +
            "  </div>\n" +
            "</aside>\n" +
            "\n" +
            "<main class='main'>\n" +
            "  <header class='header'>\n" +
            "    <h2 id='pageTitle' style='margin:0; text-transform:none; letter-spacing:normal; color:var(--text-main); font-size:1.25rem;'>Summary Overview</h2>\n" +
            "    <input type='text' id='gSearch' class='search-bar' placeholder='Search telemetry...'>\n" +
            "  </header>\n" +
            "\n" +
            "  <section id='overview' class='content active'>\n" +
            "    " + cycleSection + "\n" +
            "    <div class='grid' id='summaryGrid'></div>\n" +
            "    <div class='card'><h3>Top Suggested Improvements</h3><table id='sTable'></table></div>\n" +
            "  </section>\n" +
            "\n" +
            "  <section id='apis' class='content'>\n" +
            "    <div class='card'><h3>Downstream API Breach Analysis</h3><table id='apiTable'></table></div>\n" +
            "  </section>\n" +
            "\n" +
            "  <section id='hotspots' class='content'>\n" +
            "    <div class='card'><h3>Critical Architectural Weakpoints</h3><table id='hsTable'></table></div>\n" +
            "  </section>\n" +
            "\n" +
            "  <section id='explorer' class='content'>\n" +
            "    <div class='card'><h3>Deep Method Risk Telemetry</h3><table id='riskTable'></table></div>\n" +
            "  </section>\n" +
            "\n" +
            "  <section id='raw' class='content'>\n" +
            "    <div class='card'><h3>NullGuard Data Dump</h3><pre style='white-space:pre-wrap;'>" + sanitize(jsonOutput) + "</pre></div>\n" +
            "  </section>\n" +
            "</main>\n" +
            "\n" +
            "<script>\n" +
            "const data = Object.assign({}, " + jsonOutput + ", {\n" +
            "  apiEndpoints: " + apiJson + ",\n" +
            "  suggestions: " + suggestJson + ",\n" +
            "  hotspots: " + hotspotJson + "\n" +
            "});\n" +
            "const reasons = " + reasonJson.toString() + ";\n" +
            "const s = data.summary || {}; const nodes = (data.graph && data.graph.nodes) || {}; const edges = (data.graph && data.graph.edges) || [];\n" +
            "const inDegree = {}; edges.forEach(e => inDegree[e.to] = (inDegree[e.to]||0)+1);\n" +
            "\n" +
            "/* Each section renders inside its own guard so that one bad record or one missing\n" +
            "   collection degrades a single panel instead of blanking the entire dashboard. */\n" +
            "function panel(id, fn) {\n" +
            "  try { fn(); }\n" +
            "  catch (err) {\n" +
            "    console.error('NullGuard: failed to render ' + id, err);\n" +
            "    const el = document.getElementById(id);\n" +
            "    if (el) el.innerHTML = \"<tbody><tr><td style='color:var(--danger)'>Render error: \" + err.message + \"</td></tr></tbody>\";\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "function show(id) {\n" +
            "  document.querySelectorAll('.content').forEach(c => c.classList.remove('active'));\n" +
            "  document.getElementById(id).classList.add('active');\n" +
            "  document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));\n" +
            "  document.getElementById('nav-'+id).classList.add('active');\n" +
            "  document.getElementById('pageTitle').innerText = document.getElementById('nav-'+id).innerText;\n" +
            "}\n" +
            "\n" +
            "/* Render Summary */\n" +
            "document.getElementById('summaryGrid').innerHTML = `\n" +
            "  <div class='stat-box'><span class='stat-label'>Stability Grade</span><span class='stat-value' style='color:${s.grade==='A'?'var(--success)':'var(--danger)'}'>${s.grade}</span><span style='font-size:0.75rem'>Index: ${s.stabilityIndex.toFixed(2)}</span></div>\n" +
            "  <div class='stat-box'><span class='stat-label'>Blast Radius Index</span><span class='stat-value' style='color:var(--primary)'>${s.blastRadiusScore.toFixed(2)}</span><span style='font-size:0.75rem'>Max Risk Detected: ${s.maxRisk.toFixed(2)}</span></div>\n" +
            "  <div class='stat-box'><span class='stat-label'>Risk Distribution</span><span class='stat-value' style='color:var(--warning)'>${s.highRiskMethods}</span><span style='font-size:0.75rem'>High-risk methods detected</span></div>\n" +
            "  <div class='stat-box'><span class='stat-label'>API Coverage</span><span class='stat-value'>${s.totalMethods}</span><span style='font-size:0.75rem'>Total methods analysed</span></div>`;\n" +
            "\n" +
            "/* Render Suggestions */\n" +
            "panel('sTable', () => {\n" +
            "const rows = data.suggestions || [];\n" +
            "document.getElementById('sTable').innerHTML = `<thead><tr><th>Type</th><th>Location</th><th>Message</th><th>Impact</th></tr></thead>\n" +
            "  <tbody>${rows.length ? rows.map(s => `<tr><td><span class='badge' style='background:rgba(59,130,246,0.1);color:var(--primary)'>${s.suggestionType}</span></td><td><code>${s.methodId.split('.').pop()}</code></td><td>${s.message}</td><td><strong>${s.finalScore.toFixed(2)}</strong></td></tr>`).join('') : `<tr><td colspan='4' style='color:var(--text-muted)'>No suggestions produced.</td></tr>`}</tbody>`;\n" +
            "});\n" +
            "\n" +
            "/* Render APIs */\n" +
            "panel('apiTable', () => {\n" +
            "const apis = data.apiEndpoints || [];\n" +
            "document.getElementById('apiTable').innerHTML = `<thead><tr><th>Endpoint</th><th>Risk</th><th>Coverage</th><th>Downstream Chain</th></tr></thead>\n" +
            "  <tbody>${apis.length ? apis.map(e => `<tr>\n" +
            "    <td><span class='badge' style='background:rgba(16,185,129,0.1);color:var(--success)'>${e.httpMethod}</span> <code style='color:var(--primary)'>${e.path}</code><br><span style='font-size:0.7rem;color:var(--text-muted)'>${e.endpointId}</span></td>\n" +
            "    <td><div style='display:flex;align-items:center;gap:8px;'><progress value='${e.apiRiskScore*10}' max='100'></progress><span>${e.apiRiskScore.toFixed(2)}</span></div></td>\n" +
            "    <td><span class='badge' style='background:#27272a'>${e.propagationDepth} hops</span></td>\n" +
            "    <td><details><summary style='font-size:0.7rem;cursor:pointer;color:var(--text-muted)'>View ${(e.propagationChain||[]).length} methods</summary><div style='margin-top:0.5rem;padding:0.75rem;background:#18181b;border-radius:8px;font-size:0.75rem;'>${(e.propagationChain||[]).map(m=>`<div style='padding:2px 0;color:var(--text-muted)'>↳ ${m}</div>`).join('')}</div></details></td>\n" +
            "  </tr>`).join('') : `<tr><td colspan='4' style='color:var(--text-muted)'>No API endpoints detected. NullGuard resolves entry points from controller annotations - if this project exposes REST endpoints, check that the mapping annotations are on the classpath the analyser scanned.</td></tr>`}</tbody>`;\n" +
            "});\n" +
            "\n" +
            "/* Render Hotspots – prefer the engine's own hotspot ranking; fall back to a\n" +
            "   call-graph heuristic only when the pipeline produced no hotspots at all. */\n" +
            "panel('hsTable', () => {\n" +
            "const engineHs = (data.hotspots || []).map(h => {\n" +
            "  const n = nodes[h.methodRef] || {};\n" +
            "  return { methodId: h.methodRef, adjustedRisk: h.hotspotScore, severity: h.severity, impactMap: n.impactMap || [] };\n" +
            "});\n" +
            "const hs = engineHs.length ? engineHs.sort((a,b)=>b.adjustedRisk-a.adjustedRisk)\n" +
            "  : Object.values(nodes).filter(n => (inDegree[n.methodId]||0) > 3 || n.adjustedRisk > 40).sort((a,b)=>(inDegree[b.methodId]||0)-(inDegree[a.methodId]||0));\n" +
            "document.getElementById('hsTable').innerHTML = `<thead><tr><th>Critical Method</th><th>Calls</th><th>Risk</th><th>Blast Radius</th></tr></thead>\n" +
            "  <tbody>${hs.length ? hs.slice(0,15).map(n => `<tr><td><code style='color:var(--primary)'>${n.methodId.split('.').pop()}</code><br><span style='font-size:0.75rem;color:var(--text-muted)'>${n.methodId}</span></td><td><span class='badge' style='background:rgba(59,130,246,0.1);color:var(--primary)'>${inDegree[n.methodId]||0} calls</span></td><td><div style='display:flex;align-items:center;gap:8px;'><progress value='${n.adjustedRisk}' max='100'></progress><span style='font-size:0.75rem'>${n.adjustedRisk.toFixed(2)}</span></div></td><td><span class='badge badge-${n.severity || 'LOW'}'>${n.impactMap ? n.impactMap.length : 0} APIs Affected</span></td></tr>`).join('') : `<tr><td colspan='4' style='color:var(--text-muted)'>No hotspots above threshold.</td></tr>`}</tbody>`;\n" +
            "});\n" +
            "\n" +
            "/* Render Explorer */\n" +
            "function renderMethods(q='') {\n" +
            "  const items = Object.values(nodes).filter(n => n.methodId.toLowerCase().includes(q.toLowerCase())).sort((a,b)=>b.adjustedRisk - a.adjustedRisk);\n" +
            "  document.getElementById('riskTable').innerHTML = `<thead><tr><th>Method Signature</th><th>Risk Factors</th><th>Impact Blast Analysis</th></tr></thead>\n" +
            "    <tbody>${items.slice(0,100).map(n => {\n" +
            "      const rs = reasons[n.methodId] || [];\n" +
            "      const impact = n.impactMap || [];\n" +
            "      return `<tr>\n" +
            "        <td style='width:35%'><code style='color:var(--primary);cursor:pointer' title='${n.methodId}'>${n.methodId.includes('#') ? n.methodId.split('#')[1] : n.methodId.split('.').pop()}</code><br><span style='font-size:0.7rem;color:var(--text-muted)'>${n.methodId.includes('#') ? n.methodId.split('#')[0] : 'core'}</span></td>\n" +
            "        <td><div style='display:flex;gap:4px;margin-bottom:4px;'><span class='badge badge-${n.riskLevel}'>${n.riskLevel}</span><span class='badge' style='background:#27272a'>Score: ${n.adjustedRisk.toFixed(2)}</span></div>\n" +
            "          <details><summary style='font-size:0.7rem;cursor:pointer;color:var(--text-muted)'>View factors</summary><ul style='font-size:0.7rem;padding-left:1rem;color:var(--text-muted);margin-top:0.5rem;'>${rs.map(r=>`<li>${r}</li>`).join('')}</ul></details></td>\n" +
            "        <td>${impact.length ? `<div class='impact-box'><div class='impact-header'>⚠️ BREACH IMPACT: ${impact.length} APIs</div><ul class='impact-list'>${impact.map(c=>`<li class='impact-item'>${c.severity}: <code>${c.entryPoint.split('.').pop()}</code></li>`).join('')}</ul></div>` : '<span style=\"color:var(--success);font-size:0.8rem\">No API Impact Detected</span>'}</td>\n" +
            "      </tr>`;\n" +
            "    }).join('')}</tbody>`;\n" +
            "}\n" +
            "document.getElementById('gSearch').addEventListener('input', e => panel('riskTable', () => renderMethods(e.target.value)));\n" +
            "panel('riskTable', () => renderMethods());\n" +
            "show('overview');\n" +
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

    /**
     * Serialises a collection to a JSON array for embedding in the dashboard script.
     *
     * <p>Returns {@code []} rather than propagating on failure: a serialisation problem in
     * one collection must not take down report generation for the whole build. The failure
     * is logged so it is still visible.
     */
    private String toJson(Object value) {
        if (value == null) return "[]";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(value);
        } catch (Exception e) {
            getLog().warn("NullGuard: could not serialise dashboard section to JSON: " + e.getMessage());
            return "[]";
        }
    }
}
