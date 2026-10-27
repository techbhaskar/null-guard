# 2.0.0-preview.1 — draft release notes

Unpublished implementation preview; qualification gates remain open.

- Optional Python, ECMAScript (JavaScript/TypeScript, including JSX/TSX), C#, Go
  and Rust packs with NullGuard-owned flow checks and separate runtime dependencies.
- Explicit signed/checksummed pack installation and removal. No scan-time downloads,
  dependency restoration, application imports, tests or generators.
- Mixed-project discovery and independent analysis, schema 2.0 JSON, HTML and SARIF,
  per-project coverage diagnostics and partial-result preservation.
- Offline exact-version dependency advisory matching and optional OSV queries.
- Existing Java positional commands, Maven/Gradle integration, rule IDs and schema
  1.0 output remain available. Multi-language behavior is opt-in.

All six new languages are experimental and report-only. Stability grades,
mandatory preview finding gates, cross-language call graphs, Angular templates,
macro/generated-code expansion, new framework OpenAPI generation and advisory
reachability are unavailable. Missing packs, failed parsing and unsupported
configurations produce analysis-error status rather than a clean scan.

Local validation covers Java 21, macOS arm64 signed installs, mixed examples,
compiler-backed fixtures, report schemas and archive reproducibility. The pinned
application scans have explicit coverage gaps and have not completed the required
source-evidence accuracy review. Linux/Windows signed-asset verification remains
pending; the checked-in CI workflow prepares that platform matrix.

Do not publish this draft as a qualified release until the gates in
`qualification/README.md` are satisfied.
