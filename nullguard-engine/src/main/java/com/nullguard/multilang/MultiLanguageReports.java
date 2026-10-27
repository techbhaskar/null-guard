package com.nullguard.multilang;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.nullguard.visualization.export.JsonGraphExporter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class MultiLanguageReports {
  public void write(ObjectNode report, Path output) throws IOException {
    Files.createDirectories(output);
    Protocol.JSON
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("nullguard-report-latest.json").toFile(), report);
    Protocol.JSON
        .writerWithDefaultPrettyPrinter()
        .writeValue(output.resolve("nullguard-results.sarif").toFile(), sarif(report));
    try (Writer out =
        Files.newBufferedWriter(
            output.resolve("nullguard-dashboard-latest.html"), StandardCharsets.UTF_8)) {
      out.write(
          """
<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>NullGuard multi-language preview</title><style>
body{font:15px system-ui;margin:2rem;color:#172638;background:#f7f9fc}h1{margin-bottom:.3rem}table{width:100%;border-collapse:collapse;background:white;margin:1rem 0}td,th{text-align:left;padding:.65rem;border-bottom:1px solid #ddd;vertical-align:top}input,select,button{padding:.6rem;margin:.3rem}pre{white-space:pre-wrap} .warning{color:#963600}summary{cursor:pointer}.muted{color:#536474}</style>
<h1>NullGuard multi-language preview</h1><p id="status"></p><p class="muted">Preview findings require review. Unsupported scores, API discovery and vulnerability reachability are Not available.</p>
<h2>Projects and coverage</h2><div id="projects"></div><h2>Findings</h2>
<label>Language <select id="language"><option value="">All</option></select></label>
<label>Search <input id="search" placeholder="File, rule or message"></label><p id="count"></p><div id="findings"></div><button id="more">Show more</button>
<h2>Dependency advisories</h2><div id="advisories"></div><h2>Diagnostics and capabilities</h2><div id="diagnostics"></div>
<script id="data" type="application/json">
""");
      new JsonGraphExporter().writeForScript(report, out);
      out.write(
          """
</script><script>
const r=JSON.parse(document.getElementById('data').textContent),projects=r.projects||[],all=[];
function el(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text);return n}
function table(parent,headers,rows){const t=el('table'),head=el('tr');headers.forEach(x=>head.append(el('th',x)));t.append(head);rows.forEach(row=>{const tr=el('tr');row.forEach(x=>{const td=el('td');td.append(x instanceof Node?x:el('span',x));tr.append(td)});t.append(tr)});parent.append(t)}
document.getElementById('status').textContent='Run status: '+r.status+' • Schema '+r.schemaVersion;
const coverage=[];projects.forEach(p=>{Object.entries(p.coverage||{}).forEach(([l,c])=>coverage.push([p.project,l,p.status,c.discoveredFiles,c.analyzedFiles,c.failedFiles,c.unsupportedFiles,c.unresolvedSymbols,c.budgetExhausted?'Yes':'No',p.capabilities?.stabilityScore&&p.legacySummary?.available?p.legacySummary.stabilityIndex+' / '+p.legacySummary.grade:'Not available']));(p.findings||[]).forEach(f=>all.push({...f,project:p.project}));const details=el('details');details.append(el('summary',p.project+' — '+p.status));details.append(el('pre',JSON.stringify({capabilities:p.capabilities,coverage:p.coverage,diagnostics:p.diagnostics},null,2)));document.getElementById('diagnostics').append(details)});
table(document.getElementById('projects'),['Project','Language','Status','Discovered','Analyzed','Failed','Unsupported','Unresolved symbols','Budget exhausted','Stability score / grade'],coverage);
(r.diagnostics||[]).forEach(d=>document.getElementById('diagnostics').append(el('p',d)));
const langs=[...new Set(all.map(f=>f.language).concat(projects.flatMap(p=>p.languages||[])))].sort();langs.forEach(l=>{const o=el('option',l);o.value=l;document.getElementById('language').append(o)});
let limit=200;function render(){const lang=document.getElementById('language').value,q=document.getElementById('search').value.toLowerCase();const matches=all.filter(f=>(!lang||f.language===lang)&&JSON.stringify(f).toLowerCase().includes(q));const parent=document.getElementById('findings');parent.replaceChildren();table(parent,['Project / language','Rule / confidence','Location','Finding and evidence'],matches.slice(0,limit).map(f=>{const d=el('details');d.append(el('summary',f.message));d.append(el('pre',(f.evidence||[]).join('\\n')));return[f.project+' / '+f.language,f.ruleId+' / '+f.confidence,(f.location?.path||'')+':'+(f.location?.startLine||''),d]}));document.getElementById('count').textContent=matches.length+' findings; showing '+Math.min(matches.length,limit);document.getElementById('more').hidden=matches.length<=limit}
['language','search'].forEach(id=>document.getElementById(id).addEventListener('input',()=>{limit=200;render()}));document.getElementById('more').addEventListener('click',()=>{limit+=200;render()});render();
const vulns=projects.flatMap(p=>(p.vulnerabilities||[]).map(v=>[p.project,v.id,v.ecosystem+':'+v.name+'@'+v.version,v.message,'Not available']));table(document.getElementById('advisories'),['Project','Advisory','Dependency','Evidence','Reachability'],vulns);
</script></html>
""");
    }
  }

  public ObjectNode sarif(JsonNode report) {
    ObjectNode root = Protocol.JSON.createObjectNode();
    root.put("version", "2.1.0");
    root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
    ObjectNode run = root.putArray("runs").addObject();
    run.put("columnKind", "unicodeCodePoints");
    ObjectNode driver = run.putObject("tool").putObject("driver");
    driver.put("name", "NullGuard");
    driver.put("version", Protocol.ANALYZER_VERSION);
    ArrayNode rules = driver.putArray("rules"), results = run.putArray("results");
    Map<String, String> ids = new TreeMap<>();
    for (JsonNode p : report.path("projects"))
      for (JsonNode f : p.path("findings"))
        ids.put(f.path("ruleId").asText(), f.path("message").asText());
    for (JsonNode p : report.path("projects"))
      for (JsonNode v : p.path("vulnerabilities"))
        ids.put("NG-DEP-" + v.path("id").asText(), "Dependency advisory " + v.path("id").asText());
    ids.forEach(
        (id, msg) ->
            rules.addObject().put("id", id).putObject("shortDescription").put("text", msg));
    for (JsonNode p : report.path("projects")) {
      ObjectNode invocation = run.withArray("invocations").addObject();
      invocation.put("executionSuccessful", p.path("status").asText().equals("complete"));
      if (!p.path("status").asText().equals("complete"))
        invocation
            .putArray("toolExecutionNotifications")
            .addObject()
            .put("level", "error")
            .putObject("message")
            .put("text", p.path("diagnostics").toString());
      for (JsonNode f : p.path("findings")) {
        ObjectNode n = results.addObject();
        n.put("ruleId", f.path("ruleId").asText());
        n.put("level", f.path("severity").asText());
        n.putObject("message").put("text", f.path("message").asText());
        ObjectNode properties = n.putObject("properties");
        properties.put("language", f.path("language").asText());
        properties.put("project", p.path("project").asText());
        properties.put("confidence", f.path("confidence").asText());
        properties.set("evidence", f.path("evidence"));
        for (JsonNode related : f.path("relatedLocations")) {
          if (!related.has("path") || related.path("startLine").asInt() < 1) continue;
          ObjectNode physical =
              n.withArray("relatedLocations").addObject().putObject("physicalLocation");
          physical
              .putObject("artifactLocation")
              .put(
                  "uri",
                  Path.of(p.path("root").asText())
                      .resolve(related.path("path").asText())
                      .toUri()
                      .toASCIIString());
          physical
              .putObject("region")
              .put("startLine", related.path("startLine").asInt())
              .put("startColumn", Math.max(1, related.path("startColumn").asInt()));
        }
        JsonNode l = f.path("location");
        if (l.has("path")) {
          ObjectNode physical = n.putArray("locations").addObject().putObject("physicalLocation");
          physical
              .putObject("artifactLocation")
              .put(
                  "uri",
                  Path.of(p.path("root").asText())
                      .resolve(l.path("path").asText())
                      .toUri()
                      .toASCIIString());
          physical
              .putObject("region")
              .put("startLine", l.path("startLine").asInt())
              .put("startColumn", l.path("startColumn").asInt());
        }
      }
      for (JsonNode v : p.path("vulnerabilities")) {
        ObjectNode n = results.addObject();
        n.put("ruleId", "NG-DEP-" + v.path("id").asText());
        n.put("level", "warning");
        n.putObject("message")
            .put(
                "text",
                v.path("id").asText()
                    + ": "
                    + v.path("ecosystem").asText()
                    + ":"
                    + v.path("name").asText()
                    + "@"
                    + v.path("version").asText()
                    + " — "
                    + v.path("message").asText());
        ObjectNode props = n.putObject("properties");
        props.put("project", p.path("project").asText());
        props.put("vulnerabilityReachability", "Not available");
        props.set("dependency", v);
      }
    }
    return root;
  }
}
