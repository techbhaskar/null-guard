package com.nullguard.bootstrap;

/**
 * Renders the self-contained, offline interactive HTML dashboard from a {@link FinalAnalysisResult}.
 * Shared across Maven, Gradle, and the standalone CLI so every entry point produces the same
 * clean, modern 8-tab engineering intelligence experience.
 */
public final class HtmlDashboardRenderer {
    private HtmlDashboardRenderer() {}

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
        SwaggerUiReport.write(file.toAbsolutePath().getParent(), result.getVisualizations());
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
        String safeProject = sanitize(projectName == null || projectName.isBlank() ? "Java Service" : projectName);
        String safeTimestamp = sanitize(timestamp == null ? "" : timestamp);

        StringBuilder cycleSection = new StringBuilder();
        if (!result.getCycleWarnings().isEmpty()) {
            cycleSection.append("<div class='alert-banner alert-warning'>")
                    .append("<div class='alert-title'>Call Graph Cycle Detected (Handled Safely by Fixpoint Engine)</div>")
                    .append("<ul class='alert-list'>");
            for (String w : result.getCycleWarnings()) {
                cycleSection.append("<li><code>").append(sanitize(w)).append("</code></li>");
            }
            cycleSection.append("</ul></div>\n");
        }

        out.write("""
            <!DOCTYPE html>
            <html lang="en" data-theme="dark">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>NullGuard Intelligence — %s</title>
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
            <style>
            :root, [data-theme="dark"] {
              --bg-app: #0b0f17;
              --bg-sidebar: #0f1623;
              --bg-surface: #131c2e;
              --bg-elevated: #19243a;
              --bg-hover: rgba(255, 255, 255, 0.04);
              --bg-code: #0d1320;
              --border: #23304a;
              --border-subtle: #1b263b;
              --text-main: #f1f5f9;
              --text-secondary: #cbd5e1;
              --text-muted: #8b9bb4;
              --primary: #3b82f6;
              --primary-soft: rgba(59, 130, 246, 0.14);
              --success: #10b981;
              --success-soft: rgba(16, 185, 129, 0.14);
              --warning: #f59e0b;
              --warning-soft: rgba(245, 158, 11, 0.15);
              --danger: #ef4444;
              --danger-soft: rgba(239, 68, 68, 0.15);
              --purple: #a855f7;
              --purple-soft: rgba(168, 85, 247, 0.15);
              --shadow: 0 4px 20px rgba(0, 0, 0, 0.28);
            }
            [data-theme="light"] {
              --bg-app: #f8fafc;
              --bg-sidebar: #ffffff;
              --bg-surface: #ffffff;
              --bg-elevated: #f1f5f9;
              --bg-hover: rgba(15, 23, 42, 0.04);
              --bg-code: #f1f5f9;
              --border: #e2e8f0;
              --border-subtle: #edf2f7;
              --text-main: #0f172a;
              --text-secondary: #334155;
              --text-muted: #64748b;
              --primary: #2563eb;
              --primary-soft: rgba(37, 99, 235, 0.10);
              --success: #059669;
              --success-soft: rgba(5, 150, 105, 0.10);
              --warning: #d97706;
              --warning-soft: rgba(217, 119, 6, 0.12);
              --danger: #dc2626;
              --danger-soft: rgba(220, 38, 38, 0.10);
              --purple: #7e22ce;
              --purple-soft: rgba(126, 34, 206, 0.10);
              --shadow: 0 2px 10px rgba(15, 23, 42, 0.05);
            }
            * { box-sizing: border-box; }
            html, body { margin: 0; padding: 0; height: 100%%; font-family: 'Inter', -apple-system, BlinkMacSystemFont, system-ui, sans-serif; background: var(--bg-app); color: var(--text-main); -webkit-font-smoothing: antialiased; }
            body { display: flex; overflow: hidden; }
            code, pre, .mono { font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace; }

            /* Sidebar Navigation */
            .sidebar { width: 272px; flex-shrink: 0; background: var(--bg-sidebar); border-right: 1px solid var(--border); display: flex; flex-direction: column; padding: 1.25rem 1rem; overflow-y: auto; }
            .brand { display: flex; align-items: center; gap: 0.75rem; padding: 0.25rem 0.5rem 1.15rem; border-bottom: 1px solid var(--border-subtle); margin-bottom: 1rem; }
            .brand-logo { width: 34px; height: 34px; border-radius: 9px; background: linear-gradient(135deg, #3b82f6 0%%, #1d4ed8 100%%); display: flex; align-items: center; justify-content: center; color: #fff; font-weight: 800; font-size: 0.95rem; box-shadow: 0 2px 10px rgba(59,130,246,0.35); flex-shrink: 0; }
            .brand-title { font-weight: 700; font-size: 1rem; color: var(--text-main); line-height: 1.2; }
            .brand-sub { font-size: 0.72rem; color: var(--text-muted); font-weight: 500; margin-top: 2px; }
            .nav-group-label { font-size: 0.68rem; text-transform: uppercase; letter-spacing: 0.06em; color: var(--text-muted); font-weight: 600; padding: 0.6rem 0.65rem 0.35rem; }
            .nav { display: flex; flex-direction: column; gap: 0.25rem; }
            .nav-item { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; padding: 0.62rem 0.75rem; border-radius: 8px; color: var(--text-secondary); font-size: 0.86rem; font-weight: 500; cursor: pointer; border: 1px solid transparent; user-select: none; transition: background 0.15s, color 0.15s, border-color 0.15s; text-decoration: none; }
            .nav-item:hover { background: var(--bg-hover); color: var(--text-main); }
            .nav-item.active { background: var(--primary-soft); color: var(--primary); border-color: rgba(59,130,246,0.28); font-weight: 600; }
            .nav-count { font-size: 0.7rem; font-weight: 600; padding: 2px 7px; border-radius: 999px; background: var(--bg-elevated); color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
            .nav-item.active .nav-count { background: var(--primary); color: #fff; }
            .sidebar-footer { margin-top: auto; padding-top: 1rem; border-top: 1px solid var(--border-subtle); font-size: 0.74rem; color: var(--text-muted); display: flex; flex-direction: column; gap: 0.35rem; }
            .project-pill { background: var(--bg-elevated); border: 1px solid var(--border); border-radius: 8px; padding: 0.55rem 0.7rem; color: var(--text-secondary); font-weight: 500; word-break: break-all; }

            /* Main Workspace */
            .main { flex: 1; display: flex; flex-direction: column; overflow: hidden; min-width: 0; }
            .topbar { padding: 0.95rem 1.75rem; background: var(--bg-sidebar); border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; z-index: 10; }
            .topbar-left { display: flex; flex-direction: column; gap: 2px; }
            .page-title { margin: 0; font-size: 1.15rem; font-weight: 700; color: var(--text-main); }
            .page-subtitle { font-size: 0.78rem; color: var(--text-muted); }
            .topbar-actions { display: flex; align-items: center; gap: 0.65rem; flex-wrap: wrap; }
            .search-wrap { position: relative; display: flex; align-items: center; }
            .search-bar { background: var(--bg-elevated); border: 1px solid var(--border); color: var(--text-main); padding: 0.52rem 0.85rem; border-radius: 8px; font-size: 0.84rem; width: 310px; outline: none; transition: border-color 0.15s; }
            .search-bar:focus { border-color: var(--primary); }
            .btn { background: var(--primary); color: #fff; border: 1px solid transparent; padding: 0.48rem 0.85rem; border-radius: 8px; font-size: 0.8rem; font-weight: 600; cursor: pointer; text-decoration: none; display: inline-flex; align-items: center; gap: 0.4rem; transition: opacity 0.15s; }
            .btn:hover { opacity: 0.92; }
            .btn-ghost { background: var(--bg-elevated); color: var(--text-secondary); border: 1px solid var(--border); }
            .btn-ghost:hover { color: var(--text-main); border-color: var(--text-muted); }

            /* Content Panes */
            .workspace { flex: 1; overflow-y: auto; padding: 1.5rem 1.75rem 3rem; }
            .content { max-width: 1440px; margin: 0 auto; display: none; }
            .content.active { display: block; animation: fadeIn 0.2s ease-out; }
            @keyframes fadeIn { from { opacity: 0; transform: translateY(3px); } to { opacity: 1; transform: translateY(0); } }

            /* Pillar Score Cards */
            .pillars-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1rem; margin-bottom: 1.25rem; }
            .pillar-card { background: var(--bg-surface); border: 1px solid var(--border); border-radius: 12px; padding: 1.25rem; box-shadow: var(--shadow); display: flex; flex-direction: column; justify-content: space-between; gap: 0.9rem; }
            .pillar-top { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; }
            .pillar-label { font-size: 0.74rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted); font-weight: 600; }
            .pillar-score-row { display: flex; align-items: baseline; gap: 0.5rem; margin-top: 0.3rem; }
            .pillar-score { font-size: 2rem; font-weight: 800; line-height: 1; }
            .pillar-max { font-size: 0.85rem; color: var(--text-muted); font-weight: 500; }
            .grade-pill { width: 44px; height: 44px; border-radius: 10px; display: flex; align-items: center; justify-content: center; font-size: 1.25rem; font-weight: 800; flex-shrink: 0; }
            .grade-A { background: var(--success-soft); color: var(--success); border: 1px solid rgba(16,185,129,0.3); }
            .grade-B { background: var(--primary-soft); color: var(--primary); border: 1px solid rgba(59,130,246,0.3); }
            .grade-C, .grade-D { background: var(--warning-soft); color: var(--warning); border: 1px solid rgba(245,158,11,0.3); }
            .grade-F { background: var(--danger-soft); color: var(--danger); border: 1px solid rgba(239,68,68,0.3); }
            .pillar-meta { display: flex; flex-wrap: wrap; gap: 0.5rem; padding-top: 0.75rem; border-top: 1px solid var(--border-subtle); font-size: 0.78rem; color: var(--text-secondary); justify-content: space-between; }

            /* KPI Strip */
            .kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(175px, 1fr)); gap: 0.85rem; margin-bottom: 1.25rem; }
            .kpi-box { background: var(--bg-surface); border: 1px solid var(--border); border-radius: 10px; padding: 0.95rem 1.1rem; display: flex; flex-direction: column; gap: 0.25rem; }
            .kpi-label { font-size: 0.72rem; color: var(--text-muted); font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
            .kpi-val { font-size: 1.45rem; font-weight: 700; color: var(--text-main); }
            .kpi-sub { font-size: 0.74rem; color: var(--text-muted); }

            /* Cards & Filter Bar */
            .card { background: var(--bg-surface); border: 1px solid var(--border); border-radius: 12px; padding: 1.25rem; margin-bottom: 1.25rem; box-shadow: var(--shadow); }
            .card-header { display: flex; justify-content: space-between; align-items: center; gap: 1rem; flex-wrap: wrap; margin-bottom: 1rem; }
            .card-title { margin: 0; font-size: 1rem; font-weight: 700; color: var(--text-main); }
            .card-subtitle { margin: 0.2rem 0 0; font-size: 0.8rem; color: var(--text-muted); }
            .filter-pills { display: flex; flex-wrap: wrap; gap: 0.4rem; }
            .pill { background: var(--bg-elevated); border: 1px solid var(--border); color: var(--text-secondary); padding: 0.34rem 0.72rem; border-radius: 999px; font-size: 0.76rem; font-weight: 600; cursor: pointer; user-select: none; transition: all 0.15s; }
            .pill:hover { border-color: var(--primary); color: var(--text-main); }
            .pill.active { background: var(--primary); border-color: var(--primary); color: #fff; }

            /* Tables */
            .table-wrap { width: 100%%; overflow-x: auto; }
            table { width: 100%%; border-collapse: collapse; }
            th { text-align: left; font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted); padding: 0.75rem 0.9rem; border-bottom: 1px solid var(--border); background: var(--bg-elevated); font-weight: 600; }
            th:first-child { border-top-left-radius: 8px; }
            th:last-child { border-top-right-radius: 8px; }
            td { padding: 0.85rem 0.9rem; border-bottom: 1px solid var(--border-subtle); font-size: 0.86rem; vertical-align: top; color: var(--text-secondary); line-height: 1.45; }
            tr:last-child td { border-bottom: none; }
            tbody tr:hover td { background: var(--bg-hover); }

            /* Badges */
            .badge { padding: 3px 9px; border-radius: 6px; font-size: 0.71rem; font-weight: 600; white-space: nowrap; display: inline-flex; align-items: center; gap: 4px; font-family: 'JetBrains Mono', monospace; }
            .badge-CRITICAL, .badge-error, .badge-API_REACHABLE, .verb-DELETE { background: var(--danger-soft); color: var(--danger); border: 1px solid rgba(239,68,68,0.28); }
            .badge-HIGH, .badge-warning, .badge-CODE_REACHABLE, .verb-PUT, .verb-PATCH { background: var(--warning-soft); color: var(--warning); border: 1px solid rgba(245,158,11,0.28); }
            .badge-MEDIUM, .badge-MODERATE, .verb-POST { background: var(--primary-soft); color: var(--primary); border: 1px solid rgba(59,130,246,0.28); }
            .badge-LOW, .badge-note, .badge-UNREACHABLE, .verb-GET { background: var(--success-soft); color: var(--success); border: 1px solid rgba(16,185,129,0.28); }
            .badge-neutral { background: var(--bg-elevated); color: var(--text-secondary); border: 1px solid var(--border); }

            /* Risk Meter */
            .meter-row { display: flex; align-items: center; gap: 0.55rem; }
            .meter-track { width: 90px; height: 7px; background: var(--bg-elevated); border-radius: 99px; overflow: hidden; border: 1px solid var(--border-subtle); flex-shrink: 0; }
            .meter-fill { height: 100%%; border-radius: 99px; }

            /* Swagger Operation Cards */
            .op-card { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 0.75rem; background: var(--bg-surface); overflow: hidden; transition: border-color 0.15s; }
            .op-card:hover { border-color: rgba(59,130,246,0.45); }
            .op-summary { padding: 0.85rem 1.15rem; cursor: pointer; display: flex; align-items: center; justify-content: space-between; gap: 1rem; list-style: none; user-select: none; }
            .op-summary::-webkit-details-marker { display: none; }
            .op-route { display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap; min-width: 0; }
            .op-verb { min-width: 64px; text-align: center; justify-content: center; font-weight: 700; }
            .op-path { font-size: 0.92rem; font-weight: 600; color: var(--text-main); }
            .op-desc { font-size: 0.8rem; color: var(--text-muted); }
            .op-body { padding: 1.15rem; border-top: 1px solid var(--border); background: var(--bg-app); display: flex; flex-direction: column; gap: 1rem; }
            .op-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 1rem; }
            .sub-box { background: var(--bg-surface); border: 1px solid var(--border); border-radius: 8px; padding: 0.9rem; }
            .sub-box h4 { margin: 0 0 0.65rem 0; font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--text-muted); }
            pre.code-block { background: var(--bg-code); border: 1px solid var(--border-subtle); padding: 0.8rem; border-radius: 8px; overflow-x: auto; margin: 0; font-size: 0.78rem; color: var(--text-secondary); }

            /* Alerts & Empty States */
            .alert-banner { padding: 1rem 1.25rem; border-radius: 10px; margin-bottom: 1.25rem; border: 1px solid var(--warning); background: var(--warning-soft); }
            .alert-title { font-weight: 700; font-size: 0.85rem; color: var(--warning); margin-bottom: 0.35rem; }
            .alert-list { margin: 0; padding-left: 1.2rem; font-size: 0.8rem; color: var(--text-secondary); }
            .empty-state { text-align: center; padding: 2.25rem 1rem; color: var(--text-muted); font-size: 0.88rem; }
            .loc-tag { display: inline-block; margin-top: 3px; font-size: 0.74rem; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
            .method-title { color: var(--text-main); font-weight: 600; font-size: 0.84rem; word-break: break-word; }
            .pkg-sub { font-size: 0.73rem; color: var(--text-muted); font-family: 'JetBrains Mono', monospace; }
            </style>
            </head>
            <body>
            <aside class="sidebar">
              <div class="brand">
                <div class="brand-logo">NG</div>
                <div>
                  <div class="brand-title">NullGuard</div>
                  <div class="brand-sub">Static Intelligence v1.1</div>
                </div>
              </div>

              <div class="nav-group-label">Executive &amp; Findings</div>
              <nav class="nav">
                <a onclick="show('overview')" class="nav-item active" id="nav-overview">
                  <span>Overview</span>
                  <span class="nav-count" id="cnt-overview">0</span>
                </a>
                <a onclick="show('quality')" class="nav-item" id="nav-quality">
                  <span>Code Quality &amp; Arch</span>
                  <span class="nav-count" id="cnt-quality">0</span>
                </a>
                <a onclick="show('cves')" class="nav-item" id="nav-cves">
                  <span>Reachable CVEs</span>
                  <span class="nav-count" id="cnt-cves">0</span>
                </a>
              </nav>

              <div class="nav-group-label">API &amp; Contracts</div>
              <nav class="nav">
                <a onclick="show('swagger')" class="nav-item" id="nav-swagger">
                  <span>Swagger / OpenAPI</span>
                  <span class="nav-count" id="cnt-swagger">0</span>
                </a>
                <a onclick="show('apis')" class="nav-item" id="nav-apis">
                  <span>API Flow Mapping</span>
                  <span class="nav-count" id="cnt-apis">0</span>
                </a>
              </nav>

              <div class="nav-group-label">Deep Graph Analysis</div>
              <nav class="nav">
                <a onclick="show('hotspots')" class="nav-item" id="nav-hotspots">
                  <span>Architectural Hotspots</span>
                  <span class="nav-count" id="cnt-hotspots">0</span>
                </a>
                <a onclick="show('explorer')" class="nav-item" id="nav-explorer">
                  <span>Method Explorer</span>
                  <span class="nav-count" id="cnt-explorer">0</span>
                </a>
                <a onclick="show('raw')" class="nav-item" id="nav-raw">
                  <span>JSON Telemetry</span>
                  <span class="nav-count">JSON</span>
                </a>
              </nav>

              <div class="sidebar-footer">
                <div class="project-pill">
                  <div style="font-size:0.68rem;text-transform:uppercase;color:var(--text-muted);margin-bottom:2px;">Analyzed Target</div>
                  <div style="color:var(--text-main);font-weight:600;">%s</div>
                </div>
                <div>Run ID: <span class="mono">%s</span></div>
              </div>
            </aside>

            <main class="main">
              <header class="topbar">
                <div class="topbar-left">
                  <h1 id="pageTitle" class="page-title">Overview</h1>
                  <div id="pageSub" class="page-subtitle">Unified null-safety, architecture reliability, OpenAPI 3.0 contracts, and reachable CVE posture</div>
                </div>
                <div class="topbar-actions">
                  <div class="search-wrap">
                    <input type="search" id="gSearch" class="search-bar" placeholder="Filter active tab (method, path, rule, CVE)...">
                  </div>
                  <button class="btn btn-ghost" onclick="toggleTheme()" id="themeBtn" title="Switch Light / Dark Theme">Light Theme</button>
                  <a class="btn btn-ghost" href="nullguard-openapi.json" download="nullguard-openapi.json">OpenAPI JSON</a>
                  <a class="btn" href="nullguard-report-latest.json" download="nullguard-report-latest.json">Full Report JSON</a>
                </div>
              </header>

              <div class="workspace">
                <!-- 1. OVERVIEW -->
                <section id="overview" class="content active">
                  %s
                  <div class="pillars-grid" id="pillarsGrid"></div>
                  <div class="kpi-grid" id="summaryGrid"></div>
                  <div class="card" id="analysisCompleteness"></div>

                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">All Actionable Findings (NG0xx – NG4xx)</h3>
                        <p class="card-subtitle">Source-linked static findings across Null-Safety, Reliability/Architecture, OpenAPI Contract Drift, and Reachable CVEs</p>
                      </div>
                      <div class="filter-pills" id="findingFilterPills">
                        <span class="pill active" onclick="setFindingFilter('ALL', this)">All</span>
                        <span class="pill" onclick="setFindingFilter('NULL', this)">Null-Safety (NG0xx)</span>
                        <span class="pill" onclick="setFindingFilter('ARCH', this)">Architecture (NG2xx)</span>
                        <span class="pill" onclick="setFindingFilter('OAS', this)">OpenAPI Drift (NG3xx)</span>
                        <span class="pill" onclick="setFindingFilter('CVE', this)">Reachable CVEs (NG4xx)</span>
                        <span class="pill" onclick="setFindingFilter('SUGG', this)">Remediation (NG1xx)</span>
                      </div>
                    </div>
                    <div class="table-wrap"><table id="allFindingsTable"></table></div>
                  </div>

                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">Prioritized Remediation Suggestions</h3>
                        <p class="card-subtitle">Ranked by downstream blast-radius reduction and fixpoint risk containment</p>
                      </div>
                    </div>
                    <div class="table-wrap"><table id="sTable"></table></div>
                  </div>
                </section>

                <!-- 2. SWAGGER / OPENAPI -->
                <section id="swagger" class="content">
                  <div class="card">
                    <h3 class="card-title">Interactive Swagger UI</h3>
                    <p class="card-subtitle">Live requests are disabled unless explicitly enabled in OpenAPI configuration.</p>
                    <a class="btn btn-ghost" href="nullguard-swagger.html" target="_blank" rel="noopener">Open Swagger UI</a>
                    <iframe title="NullGuard Swagger UI" src="nullguard-swagger.html" loading="lazy" style="width:100%%;height:720px;border:0;background:white"></iframe>
                  </div>
                  <div class="card" id="swaggerHeader"></div>
                  <div id="swaggerPaths"></div>
                  <div class="card" id="swaggerSchemas"></div>
                </section>

                <!-- 3. API FLOW MAPPING -->
                <section id="apis" class="content">
                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">Downstream API Breach &amp; Blast-Radius Chains</h3>
                        <p class="card-subtitle">Traces every controller endpoint through service, client, and repository call paths</p>
                      </div>
                    </div>
                    <div class="table-wrap"><table id="apiTable"></table></div>
                  </div>
                </section>

                <!-- 4. CODE QUALITY & ARCH -->
                <section id="quality" class="content">
                  <div class="kpi-grid" id="qualityGrid"></div>
                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">Architecture, Reliability &amp; Contract Drift (NG201–NG206, NG301–NG302)</h3>
                        <p class="card-subtitle">Detects N+1 loop calls, swallowed exceptions, stateful singletons, layer bypasses, cycles, unclosed resources, and OpenAPI drift</p>
                      </div>
                      <div class="filter-pills" id="qualityFilterPills">
                        <span class="pill active" onclick="setQualityFilter('ALL', this)">All Rules</span>
                        <span class="pill" onclick="setQualityFilter('NG201', this)">NG201 Loop I/O</span>
                        <span class="pill" onclick="setQualityFilter('NG202', this)">NG202 Empty Catch</span>
                        <span class="pill" onclick="setQualityFilter('NG203', this)">NG203 Stateful Bean</span>
                        <span class="pill" onclick="setQualityFilter('NG204', this)">NG204 Layer Bypass</span>
                        <span class="pill" onclick="setQualityFilter('NG205', this)">NG205 Call Cycle</span>
                        <span class="pill" onclick="setQualityFilter('NG206', this)">NG206 Resource Leak</span>
                        <span class="pill" onclick="setQualityFilter('NG3', this)">NG301/302 OpenAPI Drift</span>
                      </div>
                    </div>
                    <div class="table-wrap"><table id="qualityTable"></table></div>
                  </div>
                </section>

                <!-- 5. REACHABLE CVES -->
                <section id="cves" class="content">
                  <div class="kpi-grid" id="cveGrid"></div>
                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">3-Tier Call-Graph Reachable Vulnerability Scanner (NG401–NG403)</h3>
                        <p class="card-subtitle">Separates exploitable API-Reachable CVEs from internal Code-Reachable and Unreachable dependency noise</p>
                      </div>
                      <div class="filter-pills" id="cveFilterPills">
                        <span class="pill active" onclick="setCveFilter('ALL', this)">All CVEs</span>
                        <span class="pill" onclick="setCveFilter('API_REACHABLE', this)">NG401 API-Reachable</span>
                        <span class="pill" onclick="setCveFilter('CODE_REACHABLE', this)">NG402 Code-Reachable</span>
                        <span class="pill" onclick="setCveFilter('UNREACHABLE', this)">NG403 Unreachable</span>
                      </div>
                    </div>
                    <div class="table-wrap"><table id="cveTable"></table></div>
                  </div>
                </section>

                <!-- 6. ARCHITECTURAL HOTSPOTS -->
                <section id="hotspots" class="content">
                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">Critical Architectural Hotspots (NG005)</h3>
                        <p class="card-subtitle">High-fan-in methods that amplify failure across multiple callers and REST endpoints</p>
                      </div>
                    </div>
                    <div class="table-wrap"><table id="hsTable"></table></div>
                  </div>
                </section>

                <!-- 7. METHOD EXPLORER -->
                <section id="explorer" class="content">
                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">Method Risk &amp; Contract Explorer</h3>
                        <p class="card-subtitle">Inspect intrinsic vs. propagated risk scores, nullability contracts, and API blast radius per method</p>
                      </div>
                      <div class="filter-pills" id="methodFilterPills">
                        <span class="pill active" onclick="setMethodFilter('ALL', this)">All Levels</span>
                        <span class="pill" onclick="setMethodFilter('CRITICAL', this)">Critical</span>
                        <span class="pill" onclick="setMethodFilter('HIGH', this)">High</span>
                        <span class="pill" onclick="setMethodFilter('MEDIUM', this)">Medium</span>
                        <span class="pill" onclick="setMethodFilter('LOW', this)">Low</span>
                      </div>
                    </div>
                    <div class="table-wrap"><table id="riskTable"></table></div>
                  </div>
                </section>

                <!-- 8. RAW JSON -->
                <section id="raw" class="content">
                  <div class="card">
                    <div class="card-header">
                      <div>
                        <h3 class="card-title">Machine-Readable Telemetry</h3>
                        <p class="card-subtitle">Inspect or copy the embedded JSON payload or OpenAPI 3.0.3 document</p>
                      </div>
                      <div style="display:flex;gap:0.5rem;">
                        <button class="btn btn-ghost" onclick="document.getElementById('rawDump').textContent=JSON.stringify(oas,null,2)">Preview OpenAPI 3.0 JSON</button>
                        <button class="btn" onclick="document.getElementById('rawDump').textContent=JSON.stringify(data,null,2)">Preview Full Report JSON</button>
                      </div>
                    </div>
                    <pre id="rawDump" class="code-block" style="max-height:70vh;">Click a button above to format and inspect the embedded JSON payload.</pre>
                  </div>
                </section>
              </div>
            </main>

            <script>
            const data = """.formatted(safeProject, safeProject, safeTimestamp, cycleSection.toString()));

        result.getVisualizations().writeJsonForScript(out);
        out.write(";\n" +
            "data.apiEndpoints = data.apiEndpoints || []; data.suggestions = data.suggestions || []; data.hotspots = data.hotspots || []; data.findings = data.findings || [];\n" +
            "const reasons = ");
        VisualizationBundle.writeValueForScript(result.getRiskReasonMap(), out);
        out.write("""
            ;
            const s = data.summary || {};
            const nodes = (data.graph && data.graph.nodes) || {};
            const edges = (data.graph && data.graph.edges) || [];
            const oas = data.openApiSpec || {};
            const arch = data.architectureSummary || {};
            const cve = data.cveSummary || {};
            const inDegree = {};
            edges.forEach(e => inDegree[e.to] = (inDegree[e.to] || 0) + 1);

            let currentTab = 'overview';
            let searchQuery = '';
            let findingFilter = 'ALL';
            let qualityFilter = 'ALL';
            let cveFilter = 'ALL';
            let swaggerVerbFilter = 'ALL';
            let methodLevelFilter = 'ALL';

            const RULE_TITLES = {
              NG001: 'Possible Null Dereference',
              NG002: 'Unchecked Nullable Parameter',
              NG003: 'Non-Null Return Violation',
              NG004: 'Caller Argument Contract Violation',
              NG005: 'Architectural Risk Hotspot',
              NG006: 'Local Object Lifecycle Null Field',
              NG101: 'Add Null Guard',
              NG102: 'Strengthen Nullability Contract',
              NG103: 'Validate External Call Return',
              NG104: 'Isolate High-Risk Call Chain',
              NG105: 'Refactor Shared Hotspot',
              NG201: 'External / DB Call Inside Loop (N+1)',
              NG202: 'Swallowed or Empty Catch Block',
              NG203: 'Stateful Mutable Singleton Field',
              NG204: 'Controller -> Repository Layer Bypass',
              NG205: 'Cyclic Call Graph Dependency',
              NG206: 'Unclosed AutoCloseable Resource',
              NG301: 'Undocumented Nullable API Response',
              NG302: 'Unvalidated @RequestBody DTO',
              NG401: 'API-Reachable Dependency CVE',
              NG402: 'Code-Reachable Dependency CVE',
              NG403: 'Unreachable Dependency CVE'
            };

            function esc(v) {
              if (v === null || v === undefined) return '';
              return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
            }

            const completeness = data.analysisCompleteness;
            document.getElementById('analysisCompleteness').innerHTML = !completeness
              ? '<div class="card-header"><div><h3 class="card-title">Analysis completeness: unavailable</h3><p>No completeness metadata in this report. Risk scores are not proof of safety.</p></div></div>'
              : `<div class="card-header"><div>
                  <h3 class="card-title">Analysis completeness: ${esc(completeness.status)}</h3>
                  <p>${esc(completeness.scope)}. Runtime observed: No.</p>
                  <p>Sources parsed: ${esc(completeness.sourceFilesParsed)} / ${esc(completeness.sourceFilesDiscovered)};
                    source files with problems: ${esc(completeness.sourceFilesWithProblems)};
                    bodies analyzed: ${esc(completeness.analyzedBodies)} / ${esc(completeness.declaredBodies)};
                    unmodeled executables: ${esc(completeness.unmodeledExecutables)}.</p>
                  <p>Symbol-resolved calls: ${esc(completeness.symbolResolvedCallSites)} / ${esc(completeness.totalCallSites)};
                    dependency calls (bodies not analyzed): ${esc(completeness.resolvedDependencyCallSites)};
                    accessor calls excluded from graph: ${esc(completeness.excludedAccessorCallSites)};
                    framework assumptions: ${(completeness.frameworkAssumptions || []).length}.</p>
                  <details><summary>Unresolved calls, failures and assumptions</summary>
                    <pre style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(JSON.stringify({
                      unresolvedCalls: completeness.unresolvedCallSites,
                      sourceDispatchWithoutBody: completeness.sourceDispatchWithoutBody,
                      generatedExecutables: completeness.generatedExecutables,
                      localLifecycleLimitations: completeness.localLifecycleLimitations,
                      constructionLimitations: completeness.constructionLimitations,
                      sourceProblems: completeness.sourceProblemPaths,
                      cfgFailures: completeness.cfgFailures,
                      analysisFailures: completeness.analysisFailures,
                      frameworkAssumptions: completeness.frameworkAssumptions
                    }, null, 2))}</pre>
                  </details>
                  <ul>${(completeness.limitations || []).map(item => '<li>' + esc(item) + '</li>').join('')}</ul>
                </div></div>`;

            function splitMethod(id) {
              if (!id) return { pkg: '', short: 'project' };
              if (id.includes('#')) {
                const [owner, sig] = id.split('#');
                const lastDot = owner.lastIndexOf('.');
                const cls = lastDot >= 0 ? owner.substring(lastDot + 1) : owner;
                const pkg = lastDot >= 0 ? owner.substring(0, lastDot) : '';
                return { pkg, short: cls + '#' + sig };
              }
              return { pkg: '', short: id };
            }

            function riskColor(score) {
              if (score >= 80) return 'var(--danger)';
              if (score >= 60) return 'var(--warning)';
              if (score >= 40) return 'var(--primary)';
              return 'var(--success)';
            }

            function panel(id, fn) {
              try { fn(); }
              catch (err) {
                console.error('NullGuard render error in ' + id, err);
                const el = document.getElementById(id);
                if (el) el.innerHTML = `<tbody><tr><td style="color:var(--danger)">Render error: ${esc(err.message)}</td></tr></tbody>`;
              }
            }

            function toggleTheme() {
              const html = document.documentElement;
              const next = html.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
              html.setAttribute('data-theme', next);
              document.getElementById('themeBtn').textContent = next === 'light' ? 'Dark Theme' : 'Light Theme';
              try { localStorage.setItem('nullguard-theme', next); } catch (e) {}
            }
            try {
              const saved = localStorage.getItem('nullguard-theme');
              if (saved === 'light' || saved === 'dark') {
                document.documentElement.setAttribute('data-theme', saved);
                document.getElementById('themeBtn').textContent = saved === 'light' ? 'Dark Theme' : 'Light Theme';
              }
            } catch (e) {}

            function show(id) {
              currentTab = id;
              document.querySelectorAll('.content').forEach(c => c.classList.remove('active'));
              document.getElementById(id).classList.add('active');
              document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
              const navEl = document.getElementById('nav-' + id);
              if (navEl) {
                navEl.classList.add('active');
                const titleSpan = navEl.querySelector('span');
                if (titleSpan) document.getElementById('pageTitle').textContent = titleSpan.textContent;
              }
            }

            const num = v => typeof v === 'number' ? v.toFixed(2) : 'N/A';

            /* Update Sidebar Counts */
            const pathEntries = [];
            for (const [p, verbs] of Object.entries((oas && oas.paths) || {})) {
              for (const [v, op] of Object.entries(verbs || {})) {
                pathEntries.push({ path: p, verb: v.toUpperCase(), op });
              }
            }
            document.getElementById('cnt-overview').textContent = (data.findings || []).length;
            document.getElementById('cnt-quality').textContent = ((arch && arch.findings) || []).length;
            document.getElementById('cnt-cves').textContent = ((cve && cve.vulnerabilities) || []).length;
            document.getElementById('cnt-swagger').textContent = pathEntries.length;
            document.getElementById('cnt-apis').textContent = (data.apiEndpoints || []).length;
            document.getElementById('cnt-hotspots').textContent = (data.hotspots || []).length;
            document.getElementById('cnt-explorer').textContent = Object.keys(nodes).length;

            /* 1. Render Executive Pillar Cards & KPI Strip */
            panel('pillarsGrid', () => {
              const nsGrade = s.grade || 'A';
              const archGrade = arch.architectureGrade || 'A';
              const secGrade = cve.securityGrade || 'A';
              document.getElementById('pillarsGrid').innerHTML = `
                <div class="pillar-card">
                  <div class="pillar-top">
                    <div>
                      <div class="pillar-label">1. Null-Safety &amp; Stability</div>
                      <div class="pillar-score-row">
                        <span class="pillar-score">${num(s.stabilityIndex)}</span>
                        <span class="pillar-max">/ 100</span>
                      </div>
                    </div>
                    <div class="grade-pill grade-${nsGrade}">${esc(nsGrade)}</div>
                  </div>
                  <div class="pillar-meta">
                    <span>High-Risk Methods: <strong>${s.highRiskMethods ?? 0}</strong> / ${s.totalMethods ?? 0}</span>
                    <span>Hotspots: <strong>${(data.hotspots || []).length}</strong></span>
                  </div>
                </div>
                <div class="pillar-card" style="cursor:pointer" onclick="show('quality')">
                  <div class="pillar-top">
                    <div>
                      <div class="pillar-label">2. Architecture &amp; Reliability</div>
                      <div class="pillar-score-row">
                        <span class="pillar-score">${num(arch.architectureScore ?? 100)}</span>
                        <span class="pillar-max">/ 100</span>
                      </div>
                    </div>
                    <div class="grade-pill grade-${archGrade}">${esc(archGrade)}</div>
                  </div>
                  <div class="pillar-meta">
                    <span>Arch &amp; Loop Issues: <strong>${arch.totalViolations ?? 0}</strong></span>
                    <span>OpenAPI Drift: <strong>${((arch.ruleCounts && ((arch.ruleCounts.NG301||0)+(arch.ruleCounts.NG302||0))) || 0)}</strong></span>
                  </div>
                </div>
                <div class="pillar-card" style="cursor:pointer" onclick="show('cves')">
                  <div class="pillar-top">
                    <div>
                      <div class="pillar-label">3. Security &amp; Reachable CVEs</div>
                      <div class="pillar-score-row">
                        <span class="pillar-score">${num(cve.securityScore ?? 100)}</span>
                        <span class="pillar-max">/ 100</span>
                      </div>
                    </div>
                    <div class="grade-pill grade-${secGrade}">${esc(secGrade)}</div>
                  </div>
                  <div class="pillar-meta">
                    <span style="color:${(cve.apiReachableCount||0)>0?'var(--danger)':'inherit'}">API-Reachable: <strong>${cve.apiReachableCount ?? 0}</strong></span>
                    <span>Code-Reachable: <strong>${cve.codeReachableCount ?? 0}</strong> · Total: <strong>${cve.totalVulnerabilities ?? 0}</strong></span>
                  </div>
                </div>`;
            });

            panel('summaryGrid', () => {
              const schemasCount = Object.keys((oas.components && oas.components.schemas) || {}).length;
              document.getElementById('summaryGrid').innerHTML = `
                <div class="kpi-box"><span class="kpi-label">Blast Radius Score</span><span class="kpi-val">${num(s.blastRadiusScore)}</span><span class="kpi-sub">Max Method Risk: ${num(s.maxRisk)}</span></div>
                <div class="kpi-box"><span class="kpi-label">REST API Operations</span><span class="kpi-val">${pathEntries.length}</span><span class="kpi-sub">${schemasCount} DTO component schemas</span></div>
                <div class="kpi-box"><span class="kpi-label">Total Findings</span><span class="kpi-val">${(data.findings || []).length}</span><span class="kpi-sub">${(data.suggestions || []).length} ranked suggestions</span></div>
                <div class="kpi-box"><span class="kpi-label">Scanned Dependencies</span><span class="kpi-val">${cve.totalDependencies ?? 0}</span><span class="kpi-sub">${cve.unreachableCount ?? 0} unreachable CVEs suppressed</span></div>`;
            });

            function setFindingFilter(f, el) {
              findingFilter = f;
              document.querySelectorAll('#findingFilterPills .pill').forEach(p => p.classList.remove('active'));
              if (el) el.classList.add('active');
              renderAllFindings();
            }

            function renderAllFindings() {
              panel('allFindingsTable', () => {
                const q = searchQuery.toLowerCase();
                const list = (data.findings || []).filter(f => {
                  const r = f.ruleId || '';
                  if (findingFilter === 'NULL' && !r.startsWith('NG0')) return false;
                  if (findingFilter === 'SUGG' && !r.startsWith('NG1')) return false;
                  if (findingFilter === 'ARCH' && !r.startsWith('NG2')) return false;
                  if (findingFilter === 'OAS' && !r.startsWith('NG3')) return false;
                  if (findingFilter === 'CVE' && !r.startsWith('NG4')) return false;
                  if (!q) return true;
                  const locStr = f.location ? `${f.location.path}:${f.location.startLine}` : '';
                  return r.toLowerCase().includes(q)
                    || (f.methodId || '').toLowerCase().includes(q)
                    || (f.message || '').toLowerCase().includes(q)
                    || locStr.toLowerCase().includes(q);
                });
                document.getElementById('allFindingsTable').innerHTML = `
                  <thead><tr><th style="width:130px">Rule</th><th style="width:105px">Severity</th><th style="width:32%">Method &amp; Source Line</th><th>Finding Details</th></tr></thead>
                  <tbody>${list.length ? list.slice(0, 250).map(f => {
                    const m = splitMethod(f.methodId);
                    const loc = f.location ? `${f.location.path}:${f.location.startLine}` : '';
                    return `<tr>
                      <td><span class="badge badge-${f.severity}">${esc(f.ruleId)}</span><div class="loc-tag">${esc(RULE_TITLES[f.ruleId] || '')}</div></td>
                      <td><span class="badge badge-${f.severity}">${esc((f.severity || '').toUpperCase())}</span></td>
                      <td><div class="method-title mono">${esc(m.short)}</div>${m.pkg ? `<div class="pkg-sub">${esc(m.pkg)}</div>` : ''}${loc ? `<div class="loc-tag">📄 ${esc(loc)}</div>` : ''}</td>
                      <td>${esc(f.message)}</td>
                    </tr>`;
                  }).join('') : `<tr><td colspan="4" class="empty-state">No findings match the current filter.</td></tr>`}</tbody>`;
              });
            }

            function renderSuggestions() {
              panel('sTable', () => {
                const q = searchQuery.toLowerCase();
                const rows = (data.suggestions || []).filter(item => !q
                  || (item.suggestionType || '').toLowerCase().includes(q)
                  || (item.methodId || '').toLowerCase().includes(q)
                  || (item.message || '').toLowerCase().includes(q));
                document.getElementById('sTable').innerHTML = `
                  <thead><tr><th style="width:180px">Strategy</th><th style="width:30%">Target Method</th><th>Actionable Guidance</th><th style="width:110px">Priority Score</th></tr></thead>
                  <tbody>${rows.length ? rows.map(item => {
                    const m = splitMethod(item.methodId);
                    return `<tr>
                      <td><span class="badge badge-MEDIUM">${esc(item.suggestionType)}</span></td>
                      <td><div class="method-title mono">${esc(m.short)}</div><div class="pkg-sub">${esc(m.pkg)}</div></td>
                      <td>${esc(item.message)}</td>
                      <td><strong class="mono">${Number(item.finalScore || 0).toFixed(2)}</strong></td>
                    </tr>`;
                  }).join('') : `<tr><td colspan="4" class="empty-state">No remediation suggestions match the current filter.</td></tr>`}</tbody>`;
              });
            }

            /* 2. Render Swagger / OpenAPI Explorer */
            function setSwaggerVerb(v, el) {
              swaggerVerbFilter = v;
              document.querySelectorAll('#swaggerVerbPills .pill').forEach(p => p.classList.remove('active'));
              if (el) el.classList.add('active');
              renderSwaggerPaths();
            }

            function toggleAllOps(openState) {
              document.querySelectorAll('#swaggerPaths details.op-card').forEach(d => d.open = openState);
            }

            function copyOpenApiSpec(btn) {
              try {
                navigator.clipboard.writeText(JSON.stringify(oas, null, 2));
                const orig = btn.textContent;
                btn.textContent = 'Copied OpenAPI JSON!';
                setTimeout(() => btn.textContent = orig, 1800);
              } catch (e) {}
            }

            panel('swaggerHeader', () => {
              const schemasObj = (oas.components && oas.components.schemas) || {};
              document.getElementById('swaggerHeader').innerHTML = `
                <div class="card-header" style="margin-bottom:0.85rem;">
                  <div>
                    <h3 class="card-title" style="font-size:1.15rem;">${esc((oas.info && oas.info.title) || 'Service API')} <span class="badge badge-MEDIUM">OpenAPI ${esc(oas.openapi || '3.0.3')}</span></h3>
                    <p class="card-subtitle">${pathEntries.length} HTTP operations · ${Object.keys(schemasObj).length} DTO schemas · Enriched with x-nullguard-* blast-radius telemetry</p>
                  </div>
                  <div style="display:flex;gap:0.5rem;flex-wrap:wrap;">
                    <button class="btn btn-ghost" onclick="toggleAllOps(true)">Expand All</button>
                    <button class="btn btn-ghost" onclick="toggleAllOps(false)">Collapse All</button>
                    <button class="btn btn-ghost" onclick="copyOpenApiSpec(this)">Copy OpenAPI JSON</button>
                    <a class="btn" href="nullguard-openapi.json" download="nullguard-openapi.json">Download nullguard-openapi.json</a>
                  </div>
                </div>
                <div class="filter-pills" id="swaggerVerbPills">
                  <span class="pill active" onclick="setSwaggerVerb('ALL', this)">All Verbs (${pathEntries.length})</span>
                  <span class="pill" onclick="setSwaggerVerb('GET', this)">GET</span>
                  <span class="pill" onclick="setSwaggerVerb('POST', this)">POST</span>
                  <span class="pill" onclick="setSwaggerVerb('PUT', this)">PUT</span>
                  <span class="pill" onclick="setSwaggerVerb('DELETE', this)">DELETE</span>
                  <span class="pill" onclick="setSwaggerVerb('PATCH', this)">PATCH</span>
                </div>`;
            });

            function renderSwaggerPaths() {
              panel('swaggerPaths', () => {
                const q = searchQuery.toLowerCase();
                const filtered = pathEntries.filter(item => {
                  if (swaggerVerbFilter !== 'ALL' && item.verb !== swaggerVerbFilter) return false;
                  if (!q) return true;
                  return item.path.toLowerCase().includes(q)
                    || item.verb.toLowerCase().includes(q)
                    || (item.op.summary || '').toLowerCase().includes(q)
                    || (item.op['x-nullguard-endpoint-id'] || '').toLowerCase().includes(q);
                });
                if (!filtered.length) {
                  document.getElementById('swaggerPaths').innerHTML = `<div class="card empty-state">No REST operations match the current filter. Annotate controllers with @RequestMapping, @GetMapping, @PostMapping, or @Path to populate this explorer.</div>`;
                  return;
                }
                document.getElementById('swaggerPaths').innerHTML = filtered.map(({ path: p, verb, op }) => {
                  const riskScore = Number(op['x-nullguard-risk-score'] || 0).toFixed(2);
                  const riskLevel = op['x-nullguard-risk-level'] || 'LOW';
                  const params = op.parameters || [];
                  const reqSchema = op.requestBody && op.requestBody.content && op.requestBody.content['application/json'] && op.requestBody.content['application/json'].schema;
                  const reqValidated = op.requestBody && op.requestBody['x-nullguard-validated'];
                  const responses = op.responses || {};
                  const chain = op['x-nullguard-blast-chain'] || [];
                  return `<details class="op-card">
                    <summary class="op-summary">
                      <div class="op-route">
                        <span class="badge op-verb verb-${verb}">${esc(verb)}</span>
                        <code class="op-path">${esc(p)}</code>
                        <span class="op-desc">${esc(op.summary || '')}</span>
                      </div>
                      <div style="display:flex;align-items:center;gap:0.5rem;flex-wrap:wrap;">
                        <span class="badge badge-${riskLevel}">Risk: ${riskScore} (${esc(riskLevel)})</span>
                        <span class="badge badge-neutral">${op['x-nullguard-propagation-depth'] || 0} hops</span>
                      </div>
                    </summary>
                    <div class="op-body">
                      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.5rem;font-size:0.8rem;color:var(--text-muted)">
                        <div>Controller Entry: <code style="color:var(--text-main)">${esc(op['x-nullguard-endpoint-id'] || '')}</code></div>
                        <div>Tag: <span class="badge badge-neutral">${esc((op.tags && op.tags[0]) || 'Controller')}</span></div>
                      </div>
                      <div class="op-grid">
                        <div class="sub-box">
                          <h4>Request Parameters (${params.length})</h4>
                          ${params.length ? `<table><thead><tr><th>Name</th><th>In</th><th>Req</th><th>Schema</th><th>Nullability</th></tr></thead><tbody>
                            ${params.map(pm => `<tr>
                              <td><code>${esc(pm.name)}</code></td>
                              <td><span class="badge badge-neutral">${esc(pm.in)}</span></td>
                              <td>${pm.required ? 'Yes' : 'No'}</td>
                              <td><code>${esc((pm.schema && (pm.schema.type || pm.schema['$ref'])) || 'string')}</code></td>
                              <td><span class="badge badge-${pm['x-nullguard-nonnull'] ? 'LOW' : 'HIGH'}">${pm['x-nullguard-nonnull'] ? 'NonNull' : 'Nullable'}</span></td>
                            </tr>`).join('')}
                          </tbody></table>` : `<div style="color:var(--text-muted);font-size:0.82rem;">No path, query, or header parameters.</div>`}
                          ${reqSchema ? `<h4 style="margin-top:1rem;">Request Body (application/json) ${reqValidated ? '<span class="badge badge-LOW">@Valid</span>' : '<span class="badge badge-HIGH">Unvalidated (NG302)</span>'}</h4><pre class="code-block">${esc(JSON.stringify(reqSchema, null, 2))}</pre>` : ''}
                        </div>
                        <div class="sub-box">
                          <h4>Responses &amp; Downstream Call Chain</h4>
                          <table><thead><tr><th>HTTP</th><th>Description</th><th>Schema</th></tr></thead><tbody>
                            ${Object.entries(responses).map(([code, r]) => `<tr>
                              <td><span class="badge badge-${code.startsWith('2')?'LOW':code.startsWith('5')?'CRITICAL':'HIGH'}">${esc(code)}</span></td>
                              <td>${esc(r.description || '')}</td>
                              <td><code>${esc((r.content && r.content['application/json'] && JSON.stringify(r.content['application/json'].schema)) || 'void')}</code></td>
                            </tr>`).join('')}
                          </tbody></table>
                          ${chain.length ? `<h4 style="margin-top:1rem;">Downstream Call Chain (${chain.length} methods)</h4><div style="display:flex;flex-direction:column;gap:4px;font-size:0.78rem;">${chain.map((step, idx) => `<div class="mono" style="color:var(--text-secondary)">${idx + 1}. ${esc(step)}</div>`).join('')}</div>` : ''}
                        </div>
                      </div>
                    </div>
                  </details>`;
                }).join('');
              });

              panel('swaggerSchemas', () => {
                const schemasObj = (oas.components && oas.components.schemas) || {};
                const entries = Object.entries(schemasObj);
                document.getElementById('swaggerSchemas').innerHTML = `
                  <div class="card-header">
                    <div>
                      <h3 class="card-title">Component DTO Schemas (${entries.length})</h3>
                      <p class="card-subtitle">Statically extracted request/response models with field nullability and required constraints</p>
                    </div>
                  </div>
                  ${entries.length ? entries.map(([name, sc]) => {
                    const props = (sc && sc.properties) || {};
                    const reqSet = new Set((sc && sc.required) || []);
                    const propEntries = Object.entries(props);
                    return `<details class="op-card">
                      <summary class="op-summary">
                        <div class="op-route"><span class="badge badge-MEDIUM">DTO</span><code class="op-path">${esc(name)}</code></div>
                        <span class="badge badge-neutral">${propEntries.length} fields</span>
                      </summary>
                      <div class="op-body">
                        ${propEntries.length ? `<table><thead><tr><th>Field</th><th>Type / Ref</th><th>Required</th><th>Nullable</th></tr></thead><tbody>
                          ${propEntries.map(([fName, fSpec]) => `<tr>
                            <td><code>${esc(fName)}</code></td>
                            <td><code>${esc(fSpec.type || fSpec['$ref'] || 'object')}${fSpec.format ? ' (' + esc(fSpec.format) + ')' : ''}</code></td>
                            <td>${reqSet.has(fName) ? '<span class="badge badge-LOW">Required</span>' : '<span class="badge badge-neutral">Optional</span>'}</td>
                            <td>${fSpec.nullable ? '<span class="badge badge-HIGH">Nullable</span>' : '<span class="badge badge-LOW">NonNull</span>'}</td>
                          </tr>`).join('')}
                        </tbody></table>` : ''}
                      </div>
                    </details>`;
                  }).join('') : `<div class="empty-state">No custom DTO component schemas referenced by controller endpoints.</div>`}`;
              });
            }

            /* 3. Render API Flow Mapping */
            function renderApis() {
              panel('apiTable', () => {
                const q = searchQuery.toLowerCase();
                const apis = (data.apiEndpoints || []).filter(e => !q
                  || (e.path || '').toLowerCase().includes(q)
                  || (e.httpMethod || '').toLowerCase().includes(q)
                  || (e.endpointId || '').toLowerCase().includes(q));
                document.getElementById('apiTable').innerHTML = `
                  <thead><tr><th style="width:32%">Endpoint Route</th><th style="width:200px">Propagated Risk</th><th style="width:120px">Call Depth</th><th>Downstream Call Chain</th></tr></thead>
                  <tbody>${apis.length ? apis.map(e => {
                    const score = Number(e.apiRiskScore || 0);
                    return `<tr>
                      <td><span class="badge verb-${esc(e.httpMethod)}">${esc(e.httpMethod)}</span> <code style="color:var(--text-main);font-weight:600;">${esc(e.path)}</code><div class="loc-tag">${esc(e.endpointId)}</div></td>
                      <td><div class="meter-row"><div class="meter-track"><div class="meter-fill" style="width:${Math.min(100, score * 10)}%;background:${riskColor(score)}"></div></div><span class="mono">${score.toFixed(2)}</span></div></td>
                      <td><span class="badge badge-neutral">${e.propagationDepth || 0} hops</span></td>
                      <td><details><summary style="font-size:0.78rem;cursor:pointer;color:var(--primary);font-weight:500;">Inspect ${(e.propagationChain || []).length} reachable methods</summary><div style="margin-top:0.5rem;padding:0.7rem;background:var(--bg-code);border-radius:8px;font-size:0.76rem;">${(e.propagationChain || []).map(m => `<div class="mono" style="padding:2px 0;color:var(--text-secondary)">↳ ${esc(m)}</div>`).join('')}</div></details></td>
                    </tr>`;
                  }).join('') : `<tr><td colspan="4" class="empty-state">No API endpoints match the current filter.</td></tr>`}</tbody>`;
              });
            }

            /* 4. Render Code Quality & Architecture (NG2xx / NG3xx) */
            function setQualityFilter(f, el) {
              qualityFilter = f;
              document.querySelectorAll('#qualityFilterPills .pill').forEach(p => p.classList.remove('active'));
              if (el) el.classList.add('active');
              renderQuality();
            }

            function renderQuality() {
              panel('qualityTable', () => {
                const rc = arch.ruleCounts || {};
                const q = searchQuery.toLowerCase();
                const qFindings = (arch.findings || []).filter(f => {
                  if (qualityFilter !== 'ALL' && !(f.ruleId || '').startsWith(qualityFilter)) return false;
                  if (!q) return true;
                  return (f.ruleId || '').toLowerCase().includes(q)
                    || (f.methodId || '').toLowerCase().includes(q)
                    || (f.message || '').toLowerCase().includes(q);
                });
                document.getElementById('qualityGrid').innerHTML = `
                  <div class="kpi-box"><span class="kpi-label">Architecture Grade</span><span class="kpi-val" style="color:${(arch.architectureGrade||'A')==='A'?'var(--success)':'var(--warning)'}">${esc(arch.architectureGrade || 'A')} (${num(arch.architectureScore ?? 100)})</span><span class="kpi-sub">${arch.totalViolations || 0} total issues</span></div>
                  <div class="kpi-box"><span class="kpi-label">Loop I/O &amp; Catch</span><span class="kpi-val">${(rc.NG201||0) + (rc.NG202||0)}</span><span class="kpi-sub">NG201: ${rc.NG201||0} · NG202: ${rc.NG202||0}</span></div>
                  <div class="kpi-box"><span class="kpi-label">Singletons &amp; Layering</span><span class="kpi-val">${(rc.NG203||0) + (rc.NG204||0)}</span><span class="kpi-sub">NG203: ${rc.NG203||0} · NG204: ${rc.NG204||0}</span></div>
                  <div class="kpi-box"><span class="kpi-label">Cycles, Leaks &amp; Drift</span><span class="kpi-val">${(rc.NG205||0) + (rc.NG206||0) + (rc.NG301||0) + (rc.NG302||0)}</span><span class="kpi-sub">NG205/206: ${(rc.NG205||0)+(rc.NG206||0)} · NG3xx: ${(rc.NG301||0)+(rc.NG302||0)}</span></div>`;

                document.getElementById('qualityTable').innerHTML = `
                  <thead><tr><th style="width:140px">Rule</th><th style="width:105px">Severity</th><th style="width:32%">Method &amp; Source Line</th><th>Architectural / Reliability Finding</th></tr></thead>
                  <tbody>${qFindings.length ? qFindings.map(f => {
                    const m = splitMethod(f.methodId);
                    const loc = f.location ? `${f.location.path}:${f.location.startLine}` : '';
                    return `<tr>
                      <td><span class="badge badge-${f.severity}">${esc(f.ruleId)}</span><div class="loc-tag">${esc(RULE_TITLES[f.ruleId] || '')}</div></td>
                      <td><span class="badge badge-${f.severity}">${esc((f.severity || '').toUpperCase())}</span></td>
                      <td><div class="method-title mono">${esc(m.short)}</div>${m.pkg ? `<div class="pkg-sub">${esc(m.pkg)}</div>` : ''}${loc ? `<div class="loc-tag">📄 ${esc(loc)}</div>` : ''}</td>
                      <td>${esc(f.message)}</td>
                    </tr>`;
                  }).join('') : `<tr><td colspan="4" class="empty-state">No architecture, reliability, or OpenAPI drift findings match the current filter.</td></tr>`}</tbody>`;
              });
            }

            /* 5. Render Reachable CVEs (NG4xx) */
            function setCveFilter(f, el) {
              cveFilter = f;
              document.querySelectorAll('#cveFilterPills .pill').forEach(p => p.classList.remove('active'));
              if (el) el.classList.add('active');
              renderCves();
            }

            function renderCves() {
              panel('cveTable', () => {
                const q = searchQuery.toLowerCase();
                const vulns = (cve.vulnerabilities || []).filter(v => {
                  if (cveFilter !== 'ALL' && v.reachability !== cveFilter) return false;
                  if (!q) return true;
                  return (v.cveId || '').toLowerCase().includes(q)
                    || (v.dependency || '').toLowerCase().includes(q)
                    || (v.summary || '').toLowerCase().includes(q);
                });
                document.getElementById('cveGrid').innerHTML = `
                  <div class="kpi-box"><span class="kpi-label">Security Grade</span><span class="kpi-val" style="color:${(cve.apiReachableCount||0)>0?'var(--danger)':'var(--success)'}">${esc(cve.securityGrade || 'A')} (${num(cve.securityScore ?? 100)})</span><span class="kpi-sub">${cve.totalDependencies || 0} dependencies scanned</span></div>
                  <div class="kpi-box"><span class="kpi-label">API-Reachable (NG401)</span><span class="kpi-val" style="color:var(--danger)">${cve.apiReachableCount || 0}</span><span class="kpi-sub">Active on public HTTP routes</span></div>
                  <div class="kpi-box"><span class="kpi-label">Code-Reachable (NG402)</span><span class="kpi-val" style="color:var(--warning)">${cve.codeReachableCount || 0}</span><span class="kpi-sub">Invoked by internal code</span></div>
                  <div class="kpi-box"><span class="kpi-label">Unreachable (NG403)</span><span class="kpi-val" style="color:var(--success)">${cve.unreachableCount || 0}</span><span class="kpi-sub">Unused in call graph</span></div>`;

                document.getElementById('cveTable').innerHTML = `
                  <thead><tr><th style="width:165px">Advisory &amp; CVSS</th><th style="width:24%">Dependency &amp; Upgrade</th><th style="width:185px">Reachability Tier</th><th>Impact &amp; Exposed Endpoints</th></tr></thead>
                  <tbody>${vulns.length ? vulns.map(v => `<tr>
                    <td><div class="method-title mono">${esc(v.cveId)}</div><span class="badge badge-${v.severity}" style="margin-top:4px;">${esc(v.severity)} · CVSS ${esc(v.cvssScore)}</span></td>
                    <td><div class="mono" style="color:var(--text-main);font-weight:600;">${esc(v.dependency)}:${esc(v.version)}</div><div class="loc-tag" style="color:var(--success)">⬆ Upgrade to ${esc(v.fixedVersion)}</div></td>
                    <td><span class="badge badge-${v.reachability}">${esc(v.ruleId)} · ${esc(v.reachability)}</span></td>
                    <td><div>${esc(v.summary)}</div>${(v.affectedEndpoints || []).length ? `<div style="margin-top:6px;font-size:0.78rem;color:var(--danger)"><strong>Exposed HTTP Routes:</strong> ${v.affectedEndpoints.map(e => `<code class="badge badge-CRITICAL">${esc(e)}</code>`).join(' ')}</div>` : ''}</td>
                  </tr>`).join('') : `<tr><td colspan="4" class="empty-state">Zero vulnerable dependencies match the current filter.</td></tr>`}</tbody>`;
              });
            }

            /* 6. Render Hotspots */
            function renderHotspots() {
              panel('hsTable', () => {
                const q = searchQuery.toLowerCase();
                const engineHs = (data.hotspots || []).map(h => {
                  const n = nodes[h.methodRef] || {};
                  return { methodId: h.methodRef, adjustedRisk: h.hotspotScore, severity: h.severity, impactMap: n.impactMap || [] };
                });
                const hs = (engineHs.length ? engineHs.sort((a,b) => b.adjustedRisk - a.adjustedRisk)
                  : Object.values(nodes).filter(n => (inDegree[n.methodId]||0) > 3 || n.adjustedRisk > 40).sort((a,b) => (inDegree[b.methodId]||0) - (inDegree[a.methodId]||0)))
                  .filter(n => !q || (n.methodId || '').toLowerCase().includes(q));

                document.getElementById('hsTable').innerHTML = `
                  <thead><tr><th style="width:42%">Hotspot Method</th><th style="width:140px">Caller Fan-In</th><th style="width:220px">Hotspot Score</th><th>API Blast Radius</th></tr></thead>
                  <tbody>${hs.length ? hs.slice(0, 30).map(n => {
                    const m = splitMethod(n.methodId);
                    const score = Number(n.adjustedRisk || 0);
                    return `<tr>
                      <td><div class="method-title mono">${esc(m.short)}</div><div class="pkg-sub">${esc(m.pkg)}</div></td>
                      <td><span class="badge badge-MEDIUM">${inDegree[n.methodId] || 0} callers</span></td>
                      <td><div class="meter-row"><div class="meter-track"><div class="meter-fill" style="width:${Math.min(100, score)}%;background:${riskColor(score)}"></div></div><span class="mono">${score.toFixed(2)}</span></div></td>
                      <td><span class="badge badge-${n.severity || 'LOW'}">${esc(n.severity || 'LOW')} · ${n.impactMap ? n.impactMap.length : 0} APIs Affected</span></td>
                    </tr>`;
                  }).join('') : `<tr><td colspan="4" class="empty-state">No architectural hotspots above threshold.</td></tr>`}</tbody>`;
              });
            }

            /* 7. Render Method Explorer */
            function setMethodFilter(level, el) {
              methodLevelFilter = level;
              document.querySelectorAll('#methodFilterPills .pill').forEach(p => p.classList.remove('active'));
              if (el) el.classList.add('active');
              renderMethods();
            }

            function renderMethods() {
              panel('riskTable', () => {
                const q = searchQuery.toLowerCase();
                const items = Object.values(nodes).filter(n => {
                  if (methodLevelFilter !== 'ALL' && n.riskLevel !== methodLevelFilter) return false;
                  if (!q) return true;
                  return (n.methodId || '').toLowerCase().includes(q);
                }).sort((a, b) => b.adjustedRisk - a.adjustedRisk);

                document.getElementById('riskTable').innerHTML = `
                  <thead><tr><th style="width:38%">Method Signature</th><th style="width:28%">Risk Level &amp; Contributing Factors</th><th>Downstream API Blast Impact</th></tr></thead>
                  <tbody>${items.length ? items.slice(0, 150).map(n => {
                    const rs = reasons[n.methodId] || [];
                    const impact = n.impactMap || [];
                    const m = splitMethod(n.methodId);
                    const score = Number(n.adjustedRisk || 0);
                    return `<tr>
                      <td><div class="method-title mono" title="${esc(n.methodId)}">${esc(m.short)}</div><div class="pkg-sub">${esc(m.pkg || 'project')}</div></td>
                      <td>
                        <div style="display:flex;align-items:center;gap:6px;margin-bottom:6px;">
                          <span class="badge badge-${n.riskLevel}">${esc(n.riskLevel)}</span>
                          <span class="badge badge-neutral">Score: ${score.toFixed(2)}</span>
                        </div>
                        ${rs.length ? `<details><summary style="font-size:0.75rem;cursor:pointer;color:var(--primary)">Inspect ${rs.length} risk factor(s)</summary><ul style="font-size:0.75rem;padding-left:1.1rem;color:var(--text-secondary);margin:0.4rem 0 0;">${rs.map(r => `<li>${esc(r)}</li>`).join('')}</ul></details>` : `<span style="font-size:0.74rem;color:var(--text-muted)">No intrinsic warnings</span>`}
                      </td>
                      <td>${impact.length ? `<div style="display:flex;flex-direction:column;gap:4px;"><div style="font-size:0.75rem;font-weight:600;color:var(--danger)">⚠️ Affects ${impact.length} API Endpoint(s)</div><div style="display:flex;flex-wrap:wrap;gap:4px;">${impact.map(c => `<span class="badge badge-${c.severity}">${esc(c.severity)}: ${esc(splitMethod(c.entryPoint).short)}</span>`).join('')}</div></div>` : `<span class="badge badge-LOW">Isolated (0 APIs affected)</span>`}</td>
                    </tr>`;
                  }).join('') : `<tr><td colspan="3" class="empty-state">No methods match the current filter.</td></tr>`}</tbody>`;
              });
            }

            function renderAllDynamic() {
              renderAllFindings();
              renderSuggestions();
              renderSwaggerPaths();
              renderApis();
              renderQuality();
              renderCves();
              renderHotspots();
              renderMethods();
            }

            document.getElementById('gSearch').addEventListener('input', e => {
              searchQuery = e.target.value || '';
              renderAllDynamic();
            });

            renderAllDynamic();
            show('overview');
            </script></body></html>""");
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
