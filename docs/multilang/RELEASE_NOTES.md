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

Local validation covers Java 21, macOS/Linux arm64 signed local installs, mixed
examples, 40 compiler-backed/review tests, report schemas and archive reproducibility.
The frozen 271-finding source review exposed false positives and uncertain contracts;
no rules have earned high-confidence status. Corrected findings still need source
review. The Linux/Windows/macOS CI pack matrix and JVM/Gradle pipeline pass. Windows
signed-pack installation and clean installation from published signed assets remain
pending. CI build artifacts are unsigned.

Do not publish this draft as a qualified release until the gates in
`qualification/README.md` are satisfied.
