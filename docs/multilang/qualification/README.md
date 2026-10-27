# Preview qualification record

Status: **not release-qualified**. All new rules remain experimental.

Native rule regression fixtures and JVM protocol tests are separate from accuracy
review. `applications.json` pins the candidate real applications. Pinning or
scanning these applications does not constitute reviewing their findings.

Release blockers:
- Complete source-evidence labels for two applications per language, at least
  50 findings or every finding if fewer, across the rules observed.
- Verify installed signed assets on Linux, Windows and macOS.
- Record repeat-build checksums and runtime/platform results.

For each reviewed finding record application commit, language, rule ID, file,
line, label (`true-positive`, `false-positive`, `uncertain`), reviewer, source
permalink and reasoning. Uncertain labels must not be counted as true positives.
No rules currently have a high-confidence qualification claim.

Local evidence:
- Java 21 Maven install/verification and legacy CLI/Maven smoke checks pass.
- Gradle integration tests pass.
- Signed archives install, scan the mixed seven-language fixture and remove from
  an empty macOS arm64 pack home using `scripts/smoke_multilang.py`.
- Schema 2.0, official SARIF schema and dashboard script checks pass.
- `reproducibility.json` records matching archive hashes from repeated local builds.
- `scan-results.json` records initial exploratory application scans; diagnostic
  excerpts are capped at ten per project. These are unreviewed observations, not
  qualification labels, and may predate the final rule corrections.

Export candidates with `scripts/review_multilang.py export`, record independent
source evidence, then use `validate` with per-language observed finding counts.
The validator rejects missing labels and never fabricates precision results.
