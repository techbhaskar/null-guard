package com.nullguard.bootstrap;

/**
 * Renders the self-contained HTML dashboard from a {@link FinalAnalysisResult}.
 * Shared by the Maven plugin and the standalone CLI so both entry points produce
 * the full interactive HTML dashboard including OpenAPI/Swagger, Static Analysis,
 * and Reachable CVE tabs.
 */
public final class HtmlDashboardRenderer {
    private HtmlDashboardRenderer() {}

    // ── HTML Dashboard ────────────────────────────────────────────────────────

    /** Builds the dashboard in memory. Prefer {@link #write} for real projects. */
    public static String render(FinalAnalysisResult result, String timestamp, String ignoredJson, String projectName) {
        java.io.StringWriter out = new java.io.StringWriter();
        try { write(out, result, timestamp, projectName); }
        catch (java.io.IOException impossible) { throw new java.io.UncheckedIOException(impossible); }
        return out.toString();
    }

    /** Streams the dashboard to {@code file} without materialising the report as a String. */
    public static void write(java.nio.file.Path file, FinalAnalysisResult result, String timestamp, String projectName)
            throws java.io.IOException {
        try (java.io.Writer out = java.nio.file.Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            write(out, result, timestamp, projectName);
        }
    }

    /**
     * Streams the dashboard. The report (which already carries findings, API endpoints,
     * hotspots, suggestions, openApiSpec, architectureSummary, and cveSummary) is embedded
     * exactly once, as script-safe JSON.
     */
    public static void write(java.io.Writer out, FinalAnalysisResult result, String timestamp, String projectName)
            throws java.io.IOException {
        String cycleSection = "";
        if (!result.getCycleWarnings().isEmpty()) {
            StringBuilder cwHtml = new StringBuilder();
            cwHtml.append("<div class='warn-panel'><h3>Call Graph Cycle Warnings</h3><ul>\n");
            for (String w : result.getCycleWarnings()) cwHtml.append("<li>").append(sanitize(w)).append("</li>\n");
            cwHtml.append("</ul></div>\n");
            cycleSection = cwHtml.toString();
        }

        out.write("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><title>NullGuard Stability Intelligence</title>\n" +
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
            ".sidebar { width: 280px; border-right: 1px solid var(--border); display: flex; flex-direction: column; padding: 1.5rem; gap: 1.5rem; overflow-y: auto; }\n" +
            ".brand { display: flex; align-items: center; gap: 0.75rem; font-weight: 700; font-size: 1.25rem; color: var(--primary); }\n" +
            ".nav { display: flex; flex-direction: column; gap: 0.35rem; }\n" +
            ".nav-item { padding: 0.7rem 1rem; border-radius: 8px; color: var(--text-muted); text-decoration: none; font-size: 0.9rem; font-weight: 500; cursor: pointer; display: flex; justify-content: space-between; align-items: center; }\n" +
            ".nav-item:hover { background: rgba(255,255,255,0.05); color: var(--text-main); }\n" +
            ".nav-item.active { background: var(--accent); color: white; }\n" +
            "\n" +
            "/* Main Content */\n" +
            ".main { flex: 1; overflow-y: auto; display: flex; flex-direction: column; }\n" +
            ".header { padding: 1.5rem 2rem; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; position: sticky; top:0; background: rgba(9,9,11,0.8); backdrop-filter: blur(12px); z-index: 10; }\n" +
            ".content { padding: 2rem; max-width: 1400px; margin: 0 auto; width: 100%; display: none; }\n" +
            ".content.active { display: block; animation: fadeIn 0.4s ease; }\n" +
            "\n" +
            "/* Cards & Layout */\n" +
            ".card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 1.5rem; margin-bottom: 1.5rem; }\n" +
            ".grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 1.25rem; margin-bottom: 2rem; }\n" +
            ".stat-box { border: 1px solid var(--border); padding: 1.25rem; border-radius: 12px; display: flex; flex-direction: column; gap: 0.4rem; }\n" +
            ".stat-label { font-size: 0.72rem; color: var(--text-muted); text-transform: uppercase; font-weight: 600; }\n" +
            ".stat-value { font-size: 1.85rem; font-weight: 700; }\n" +
            "\n" +
            "/* Tables */\n" +
            "table { width: 100%; border-collapse: collapse; margin-top: 1rem; }\n" +
            "th { text-align: left; font-size: 0.75rem; text-transform: uppercase; color: var(--text-muted); padding: 1rem; border-bottom: 1px solid var(--border); }\n" +
            "td { padding: 1rem; border-bottom: 1px solid var(--border); font-size: 0.9rem; vertical-align: top; }\n" +
            "tr:hover td { background: rgba(255,255,255,0.02); }\n" +
            "\n" +
            "/* Badges & Monospace */\n" +
            "code, pre { font-family: 'JetBrains Mono', monospace; font-size: 0.85rem; }\n" +
            ".badge { padding: 4px 10px; border-radius: 99px; font-size: 0.7rem; font-weight: 600; white-space: nowrap; display: inline-block; }\n" +
            ".badge-CRITICAL, .badge-error, .badge-API_REACHABLE, .verb-DELETE { background: rgba(239,68,68,0.2); color: #fca5a5; }\n" +
            ".badge-HIGH, .badge-warning, .badge-CODE_REACHABLE, .verb-PUT, .verb-PATCH { background: rgba(245,158,11,0.2); color: #fcd34d; }\n" +
            ".badge-MEDIUM, .badge-MODERATE, .verb-POST { background: rgba(59,130,246,0.2); color: #93c5fd; }\n" +
            ".badge-LOW, .badge-note, .badge-UNREACHABLE, .verb-GET { background: rgba(16,185,129,0.2); color: #6ee7b7; }\n" +
            "progress { width: 100%; height: 6px; border-radius: 3px; appearance: none; }\n" +
            "progress::-webkit-progress-bar { background: var(--border); border-radius: 3px; }\n" +
            "progress::-webkit-progress-value { background: var(--primary); border-radius: 3px; }\n" +
            "\n" +
            "/* Swagger & Accordions */\n" +
            ".op-card { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 0.85rem; background: #121216; overflow: hidden; }\n" +
            ".op-summary { padding: 0.9rem 1.25rem; cursor: pointer; display: flex; align-items: center; justify-content: space-between; gap: 1rem; list-style: none; }\n" +
            ".op-summary::-webkit-details-marker { display: none; }\n" +
            ".op-body { padding: 1.25rem; border-top: 1px solid var(--border); background: #0c0c0f; }\n" +
            ".btn { background: var(--primary); color: white; border: none; padding: 0.5rem 1rem; border-radius: 8px; cursor: pointer; font-weight: 600; font-size: 0.8rem; text-decoration: none; display: inline-block; }\n" +
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
            "    <a onclick=\"show('overview')\" class='nav-item active' id='nav-overview'><span>Overview</span></a>\n" +
            "    <a onclick=\"show('swagger')\" class='nav-item' id='nav-swagger'><span>Swagger / OpenAPI</span></a>\n" +
            "    <a onclick=\"show('apis')\" class='nav-item' id='nav-apis'><span>API Flow Mapping</span></a>\n" +
            "    <a onclick=\"show('quality')\" class='nav-item' id='nav-quality'><span>Code Quality &amp; Arch</span></a>\n" +
            "    <a onclick=\"show('cves')\" class='nav-item' id='nav-cves'><span>Reachable CVEs</span></a>\n" +
            "    <a onclick=\"show('hotspots')\" class='nav-item' id='nav-hotspots'><span>Architectural Hotspots</span></a>\n" +
            "    <a onclick=\"show('explorer')\" class='nav-item' id='nav-explorer'><span>Method Explorer</span></a>\n" +
            "    <a onclick=\"show('raw')\" class='nav-item' id='nav-raw'><span>JSON Telemetry</span></a>\n" +
            "  </nav>\n" +
            "  <div style='margin-top:auto; font-size:0.75rem; color:var(--text-muted);'>\n" +
            "    Analysis: " + timestamp + "<br>Project: " + sanitize(projectName) + "\n" +
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
            "  <section id='swagger' class='content'>\n" +
            "    <div class='card' id='swaggerHeader'></div>\n" +
            "    <div id='swaggerPaths'></div>\n" +
            "    <div class='card' id='swaggerSchemas'></div>\n" +
            "  </section>\n" +
            "\n" +
            "  <section id='apis' class='content'>\n" +
            "    <div class='card'><h3>Downstream API Breach Analysis</h3><table id='apiTable'></table></div>\n" +
            "  </section>\n" +
            "\n" +
            "  <section id='quality' class='content'>\n" +
            "    <div class='grid' id='qualityGrid'></div>\n" +
            "    <div class='card'><h3>Architecture, Reliability &amp; Contract Drift Findings (NG2xx / NG3xx)</h3><table id='qualityTable'></table></div>\n" +
            "  </section>\n" +
            "\n" +
            "  <section id='cves' class='content'>\n" +
            "    <div class='grid' id='cveGrid'></div>\n" +
            "    <div class='card'><h3>3-Tier Reachable Dependency Vulnerabilities (NG4xx)</h3><table id='cveTable'></table></div>\n" +
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
            "    <div class='card'><h3>NullGuard Data Dump</h3><p>The full machine-readable report is in <a href='nullguard-report-latest.json' style='color:var(--primary)'>nullguard-report-latest.json</a> and <a href='nullguard-openapi.json' style='color:var(--primary)'>nullguard-openapi.json</a> next to this page. <button class='btn' onclick=\"document.getElementById('rawDump').textContent=JSON.stringify(data,null,2)\">Show here</button></p><pre id='rawDump' style='white-space:pre-wrap;'></pre></div>\n" +
            "  </section>\n" +
            "</main>\n" +
            "\n" +
            "<script>\n" +
            "const data = ");
        result.getVisualizations().writeJsonForScript(out);
        out.write(";\n" +
            "data.apiEndpoints = data.apiEndpoints || []; data.suggestions = data.suggestions || []; data.hotspots = data.hotspots || [];\n" +
            "const reasons = ");
        VisualizationBundle.writeValueForScript(result.getRiskReasonMap(), out);
        out.write(";\n" +
            "const s = data.summary || {}; const nodes = (data.graph && data.graph.nodes) || {}; const edges = (data.graph && data.graph.edges) || [];\n" +
            "const oas = data.openApiSpec || {}; const arch = data.architectureSummary || {}; const cve = data.cveSummary || {};\n" +
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
            "const num = v => typeof v === 'number' ? v.toFixed(2) : 'N/A';\n" +
            "panel('summaryGrid', () => {\n" +
            "document.getElementById('summaryGrid').innerHTML = `\n" +
            "  <div class='stat-box'><span class='stat-label'>Null-Safety Grade</span><span class='stat-value' style='color:${s.grade==='A'?'var(--success)':'var(--danger)'}'>${s.grade || 'N/A'}</span><span style='font-size:0.75rem'>Index: ${num(s.stabilityIndex)}</span></div>\n" +
            "  <div class='stat-box'><span class='stat-label'>Blast Radius Index</span><span class='stat-value' style='color:var(--primary)'>${num(s.blastRadiusScore)}</span><span style='font-size:0.75rem'>Max Risk: ${num(s.maxRisk)}</span></div>\n" +
            "  <div class='stat-box'><span class='stat-label'>High-Risk Methods</span><span class='stat-value' style='color:var(--warning)'>${s.highRiskMethods ?? 0}</span><span style='font-size:0.75rem'>Of ${s.totalMethods ?? 0} methods</span></div>\n" +
            "  <div class='stat-box'><span class='stat-label'>Architecture Health</span><span class='stat-value' style='color:${(arch.architectureGrade||'A')==='A'?'var(--success)':'var(--warning)'}'>${arch.architectureGrade || 'A'}</span><span style='font-size:0.75rem'>Score: ${num(arch.architectureScore ?? 100)} (${arch.totalViolations || 0} issues)</span></div>\n" +
            "  <div class='stat-box'><span class='stat-label'>CVE Exposure</span><span class='stat-value' style='color:${(cve.apiReachableCount||0)>0?'var(--danger)':'var(--success)'}'>${cve.securityGrade || 'A'}</span><span style='font-size:0.75rem'>${cve.apiReachableCount || 0} API-Reachable / ${cve.totalVulnerabilities || 0} CVEs</span></div>`;\n" +
            "});\n" +
            "\n" +
            "/* Render Swagger / OpenAPI Explorer */\n" +
            "panel('swaggerHeader', () => {\n" +
            "  const pathsObj = oas.paths || {};\n" +
            "  const schemasObj = (oas.components && oas.components.schemas) || {};\n" +
            "  const pathKeys = Object.keys(pathsObj);\n" +
            "  document.getElementById('swaggerHeader').innerHTML = `<div style='display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:1rem;'>\n" +
            "    <div><h3 style='margin:0 0 0.35rem 0;'>${(oas.info && oas.info.title) || 'Service API'} <span class='badge badge-MEDIUM'>OpenAPI ${oas.openapi || '3.0.3'}</span></h3>\n" +
            "    <div style='font-size:0.85rem;color:var(--text-muted)'>${pathKeys.length} paths · ${Object.keys(schemasObj).length} DTO schemas · Statically extracted without booting the application</div></div>\n" +
            "    <div style='display:flex;gap:0.75rem;'><a class='btn' href='nullguard-openapi.json' download='nullguard-openapi.json'>Download OpenAPI JSON</a></div></div>`;\n" +
            "  if (!pathKeys.length) {\n" +
            "    document.getElementById('swaggerPaths').innerHTML = `<div class='card' style='color:var(--text-muted)'>No REST endpoints discovered. Annotate controllers with @GetMapping/@PostMapping/@RequestMapping/@Path to populate the OpenAPI explorer.</div>`;\n" +
            "  } else {\n" +
            "    const cards = [];\n" +
            "    for (const [p, verbs] of Object.entries(pathsObj)) {\n" +
            "      for (const [v, op] of Object.entries(verbs)) {\n" +
            "        const upperV = v.toUpperCase();\n" +
            "        const riskScore = Number(op['x-nullguard-risk-score'] || 0).toFixed(2);\n" +
            "        const riskLevel = op['x-nullguard-risk-level'] || 'LOW';\n" +
            "        const params = op.parameters || [];\n" +
            "        const reqSchema = op.requestBody && op.requestBody.content && op.requestBody.content['application/json'] && op.requestBody.content['application/json'].schema;\n" +
            "        const responses = op.responses || {};\n" +
            "        cards.push(`<details class='op-card'><summary class='op-summary'>\n" +
            "          <div style='display:flex;align-items:center;gap:0.85rem;'><span class='badge verb-${upperV}'>${upperV}</span><code style='font-size:0.95rem;color:var(--text-main)'>${p}</code><span style='font-size:0.8rem;color:var(--text-muted)'>${op.summary || ''}</span></div>\n" +
            "          <div style='display:flex;align-items:center;gap:0.5rem;'><span class='badge badge-${riskLevel}'>Null Risk: ${riskScore} (${riskLevel})</span><span class='badge' style='background:#27272a'>${op['x-nullguard-propagation-depth'] || 0} hops</span></div>\n" +
            "        </summary><div class='op-body'>\n" +
            "          <div style='margin-bottom:0.75rem;font-size:0.8rem;color:var(--text-muted)'>Entry Method: <code>${op['x-nullguard-endpoint-id'] || ''}</code></div>\n" +
            "          ${params.length ? `<h4>Parameters</h4><table><thead><tr><th>Name</th><th>In</th><th>Required</th><th>Type</th><th>Null-Safety</th></tr></thead><tbody>${params.map(pm => `<tr><td><code>${pm.name}</code></td><td>${pm.in}</td><td>${pm.required ? 'Yes' : 'No'}</td><td><code>${(pm.schema && (pm.schema.type || pm.schema['$ref'])) || 'object'}</code></td><td><span class='badge badge-${pm['x-nullguard-nonnull'] ? 'LOW' : 'HIGH'}'>${pm['x-nullguard-nonnull'] ? 'NonNull' : 'Nullable'}</span></td></tr>`).join('')}</tbody></table>` : ''}\n" +
            "          ${reqSchema ? `<h4 style='margin-top:1rem;'>Request Body (application/json)</h4><pre style='background:#18181b;padding:0.75rem;border-radius:8px;'>${JSON.stringify(reqSchema, null, 2)}</pre>` : ''}\n" +
            "          <h4 style='margin-top:1rem;'>Responses</h4><table><thead><tr><th>Status</th><th>Description</th><th>Schema</th></tr></thead><tbody>\n" +
            "            ${Object.entries(responses).map(([code, r]) => `<tr><td><span class='badge badge-${code.startsWith('2')?'LOW':code.startsWith('5')?'CRITICAL':'HIGH'}'>${code}</span></td><td>${r.description || ''}</td><td><code>${(r.content && r.content['application/json'] && JSON.stringify(r.content['application/json'].schema)) || '—'}</code></td></tr>`).join('')}\n" +
            "          </tbody></table>\n" +
            "        </div></details>`);\n" +
            "      }\n" +
            "    }\n" +
            "    document.getElementById('swaggerPaths').innerHTML = cards.join('');\n" +
            "  }\n" +
            "  const schemaEntries = Object.entries(schemasObj);\n" +
            "  document.getElementById('swaggerSchemas').innerHTML = `<h3>Component DTO Schemas (${schemaEntries.length})</h3>` +\n" +
            "    (schemaEntries.length ? schemaEntries.map(([name, sc]) => `<details style='margin-bottom:0.6rem;'><summary style='cursor:pointer;font-weight:600;color:var(--primary)'><code>${name}</code></summary><pre style='background:#18181b;padding:0.75rem;border-radius:8px;margin-top:0.5rem;'>${JSON.stringify(sc, null, 2)}</pre></details>`).join('') : `<p style='color:var(--text-muted)'>No custom DTO component schemas referenced by endpoints.</p>`);\n" +
            "});\n" +
            "\n" +
            "/* Render Code Quality & Architecture (NG2xx / NG3xx) */\n" +
            "panel('qualityTable', () => {\n" +
            "  const rc = arch.ruleCounts || {};\n" +
            "  const qFindings = arch.findings || [];\n" +
            "  document.getElementById('qualityGrid').innerHTML = `\n" +
            "    <div class='stat-box'><span class='stat-label'>Architecture Grade</span><span class='stat-value' style='color:${(arch.architectureGrade||'A')==='A'?'var(--success)':'var(--warning)'}'>${arch.architectureGrade || 'A'}</span><span style='font-size:0.75rem'>Score: ${num(arch.architectureScore ?? 100)} / 100</span></div>\n" +
            "    <div class='stat-box'><span class='stat-label'>N+1 / Loop I/O (NG201)</span><span class='stat-value'>${rc.NG201 || 0}</span><span style='font-size:0.75rem'>Swallowed Catch (NG202): ${rc.NG202 || 0}</span></div>\n" +
            "    <div class='stat-box'><span class='stat-label'>Stateful Beans (NG203)</span><span class='stat-value'>${rc.NG203 || 0}</span><span style='font-size:0.75rem'>Layer Bypass (NG204): ${rc.NG204 || 0}</span></div>\n" +
            "    <div class='stat-box'><span class='stat-label'>Cycles &amp; Leaks (NG205/206)</span><span class='stat-value'>${(rc.NG205||0)+(rc.NG206||0)}</span><span style='font-size:0.75rem'>OpenAPI Drift (NG3xx): ${(rc.NG301||0)+(rc.NG302||0)}</span></div>`;\n" +
            "  document.getElementById('qualityTable').innerHTML = `<thead><tr><th>Rule</th><th>Severity</th><th>Method / Location</th><th>Message</th></tr></thead>\n" +
            "    <tbody>${qFindings.length ? qFindings.map(f => `<tr><td><span class='badge badge-${f.severity}'>${f.ruleId}</span></td><td>${f.severity.toUpperCase()}</td><td><code>${f.methodId}</code><br><span style='font-size:0.75rem;color:var(--text-muted)'>${f.location ? f.location.path + ':' + f.location.startLine : ''}</span></td><td>${f.message}</td></tr>`).join('') : `<tr><td colspan='4' style='color:var(--text-muted)'>Zero architectural, reliability, or OpenAPI contract-drift violations detected.</td></tr>`}</tbody>`;\n" +
            "});\n" +
            "\n" +
            "/* Render Reachable CVEs (NG4xx) */\n" +
            "panel('cveTable', () => {\n" +
            "  const vulns = cve.vulnerabilities || [];\n" +
            "  document.getElementById('cveGrid').innerHTML = `\n" +
            "    <div class='stat-box'><span class='stat-label'>Security Grade</span><span class='stat-value' style='color:${(cve.apiReachableCount||0)>0?'var(--danger)':'var(--success)'}'>${cve.securityGrade || 'A'}</span><span style='font-size:0.75rem'>Score: ${num(cve.securityScore ?? 100)} / 100</span></div>\n" +
            "    <div class='stat-box'><span class='stat-label'>API-Reachable (NG401)</span><span class='stat-value' style='color:var(--danger)'>${cve.apiReachableCount || 0}</span><span style='font-size:0.75rem'>Exposed on public HTTP routes</span></div>\n" +
            "    <div class='stat-box'><span class='stat-label'>Code-Reachable (NG402)</span><span class='stat-value' style='color:var(--warning)'>${cve.codeReachableCount || 0}</span><span style='font-size:0.75rem'>Called by internal methods</span></div>\n" +
            "    <div class='stat-box'><span class='stat-label'>Unreachable (NG403)</span><span class='stat-value' style='color:var(--success)'>${cve.unreachableCount || 0}</span><span style='font-size:0.75rem'>Of ${cve.totalDependencies || 0} dependencies scanned</span></div>`;\n" +
            "  document.getElementById('cveTable').innerHTML = `<thead><tr><th>CVE / Rule</th><th>Dependency</th><th>Reachability</th><th>Exposed Endpoints &amp; Advisory</th></tr></thead>\n" +
            "    <tbody>${vulns.length ? vulns.map(v => `<tr>\n" +
            "      <td><strong>${v.cveId}</strong><br><span class='badge badge-${v.severity}'>${v.severity} (CVSS ${v.cvssScore})</span></td>\n" +
            "      <td><code>${v.dependency}:${v.version}</code><br><span style='font-size:0.75rem;color:var(--success)'>Fix: ${v.fixedVersion}</span></td>\n" +
            "      <td><span class='badge badge-${v.reachability}'>${v.ruleId} · ${v.reachability}</span></td>\n" +
            "      <td><div>${v.summary}</div>${(v.affectedEndpoints||[]).length ? `<div style='margin-top:0.35rem;font-size:0.75rem;color:var(--danger)'>Exposed APIs: ${v.affectedEndpoints.map(e=>`<code>${e}</code>`).join(', ')}</div>` : ''}</td>\n" +
            "    </tr>`).join('') : `<tr><td colspan='4' style='color:var(--text-muted)'>Zero vulnerable dependencies detected across project manifests and classpath.</td></tr>`}</tbody>`;\n" +
            "});\n" +
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
            "</script></body></html>");
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
