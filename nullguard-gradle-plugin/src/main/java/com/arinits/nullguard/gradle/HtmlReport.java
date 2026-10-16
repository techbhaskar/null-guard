package com.arinits.nullguard.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline HTML report with text-only rendering of untrusted project findings. */
final class HtmlReport {
    private HtmlReport() {}
    static void write(Path directory) throws IOException {
        String json = Files.readString(directory.resolve("nullguard-report-latest.json"))
                .replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        String page = """
                <!doctype html><html lang="en"><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>NullGuard analysis</title>
                <style>
                body{font:16px system-ui,sans-serif;color:#172033;background:#f6f8fc;margin:0;padding:32px}
                main{max-width:1200px;margin:auto}h1{margin-bottom:8px}a{color:#1749b8}
                .panel{background:white;border:1px solid #dce2ed;border-radius:12px;padding:20px;margin:20px 0}
                input{font:inherit;padding:10px;width:min(90%,500px);border:1px solid #8c97ac;border-radius:6px}
                table{width:100%;border-collapse:collapse;text-align:left}th,td{padding:12px;border-bottom:1px solid #dce2ed;vertical-align:top;overflow-wrap:anywhere}
                .scroll{overflow:auto}#status{font-size:1.2rem}.muted{color:#526077}
                </style><main><h1>NullGuard analysis</h1>
                <p class="muted">Review individual findings. A project grade is not proof of null-safety.</p>
                <p><a href="nullguard-report-latest.json">JSON report</a> · <a href="nullguard-results.sarif">SARIF report</a></p>
                <section class="panel"><h2>Summary</h2><p id="status"></p><p id="counts"></p></section>
                <section class="panel"><h2>Findings</h2><label for="filter">Filter by rule, file or message</label><br><br>
                <input id="filter" type="search"><p id="shown" aria-live="polite"></p>
                <div class="scroll"><table><thead><tr><th>Rule</th><th>Severity</th><th>Location</th><th>Message</th></tr></thead>
                <tbody id="rows"></tbody></table></div></section>
                <script type="application/json" id="report-data">""" + json + """
                </script><script>
                const data=JSON.parse(document.getElementById('report-data').textContent);
                const summary=data.summary||{};const findings=data.findings||[];
                document.getElementById('status').textContent=summary.available===false?'Analysis unavailable — inspect source parsing and configuration.':
                    'Grade '+(summary.grade||'N/A')+' · Stability index '+(summary.stabilityIndex==null?'N/A':Number(summary.stabilityIndex).toFixed(2));
                document.getElementById('counts').textContent=(summary.totalMethods||0)+' methods · '+findings.length+' findings · '+(data.apiEndpoints||[]).length+' API endpoints';
                function render(){const query=document.getElementById('filter').value.toLowerCase();const rows=document.getElementById('rows');rows.replaceChildren();
                    const matches=findings.filter(f=>JSON.stringify(f).toLowerCase().includes(query));
                    document.getElementById('shown').textContent=matches.length+' of '+findings.length+' findings';
                    for(const f of matches){const row=document.createElement('tr');const location=f.location||{};
                        for(const value of [f.ruleId,f.severity,location.path?location.path+':'+(location.startLine||'?'):'Location unavailable',f.message]){
                            const cell=document.createElement('td');cell.textContent=value||'';row.appendChild(cell);}rows.appendChild(row);}}
                document.getElementById('filter').addEventListener('input',render);render();
                </script></main></html>
                """;
        Files.writeString(directory.resolve("nullguard-dashboard-latest.html"), page);
    }
}
