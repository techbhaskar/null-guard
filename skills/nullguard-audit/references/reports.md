# Reading reports honestly

Read `nullguard-report-latest.json` from the current invocation, not a convenient old report. Keep each project/module identifiable. Native HTML and SARIF are useful views of the same run.

## Java schema 1.0

Inspect `summary`, `graph`, `findings`, `apiEndpoints`, `hotspots`, `suggestions` and `analysisCompleteness` when available. Source verification remains required; use reported graph edges to explain supported Java caller/API exposure.

| Completeness status | Interpretation |
| --- | --- |
| `SOURCE_ANALYZED` | Supplied source bodies analyzed; no runtime proof |
| `LIMITED` | Symbol resolution, framework assumptions or modeling boundaries limit conclusions |
| `INCOMPLETE` | Parse/model/analysis failures left gaps |
| `NO_SOURCE` | No supplied sources; not a clean project |
| `UNAVAILABLE` or missing | Completeness cannot be established |

Show unresolved calls, unmodeled bodies, parse problems and dependency boundaries relevant to conclusions. `NaN`, missing or nonfinite scores are unavailable, not a perfect grade. Report `sastSummary` separately; missing compiled classes mean SAST may not have run. A native hotspot gate does not fail on every null warning. API-reachable CVE checks may also affect Java's policy when such inputs are enabled.

The Gradle integration and Java multi-language adapter in 2.0.1 analyze original source files directly. Resolve relative finding paths against the reported original project root. Published 2.0.0 Gradle reports still reference a temporary staged source tree; map those locations to original roots before editing. Separate module reports do not establish cross-module flow.

## Multi-language schema 2.0

The top-level `status` is `complete`, `partial` or `failed`; inspect every element of `projects` plus top-level diagnostics. Each project contains its own languages, status, findings, coverage, diagnostics and capabilities. Findings include rule ID, language, severity, confidence, message, origin/guard evidence, location and optional related locations.

For each language's coverage show discovered/analyzed/failed/unsupported files, unresolved symbols, missing dependencies and exhausted budgets. `complete` means the engine completed its supported analysis, not that coverage is exhaustive or findings are absent. Unknown symbols cannot prove safety. Preserve the source version's preview and experimental confidence labeling.

No cross-language edges are inferred. Pack output does not supply Java stability grades, new OpenAPI generation or advisory reachability. Exact dependency version advisory matches do not prove an affected execution path.

Native multi-language CLI exits: `0` completed projects (findings may exist), `2` invalid usage, `3` partial coverage or runtime/pack/analysis failure. Retained reports on a failed run are useful partial evidence, not a passing run. Do not apply Java `--fail-build` to this mode.

## Suggested audit output

State scope and versions, then coverage limits, then prioritized source-verified findings and next actions. For each actionable finding give file/line, evidence, consequence, proposed fix and test. Distinguish confirmed, plausible and uncertain findings without silently promoting engine confidence. Include report paths and tests/rerun results when edits were requested.
