# Multi-language qualification record

Status: **distribution installation verified; analyzer accuracy not qualified**.
All new rules remain experimental and report-only.

The frozen baseline and supplemental source review contains **271 labels** across
Python, JavaScript, TypeScript, C#, Go and Rust: 128 false positives, 143 uncertain,
and no confirmed defects. This is Codex source inspection, not independent human
validation. Uncertain findings do not count as correct. The sample does not establish
general precision or recall, and no rule qualifies for high confidence.

`baseline-*` and `reviews-baseline.json` preserve the original 237-finding sample.
`supplemental-rust-review.json` adds 34 findings from the pinned `just` application
so Rust covers multiple rules. `reviews-combined.json`, `reviewed-counts.json` and
`review-summary.json` record the combined evidence and validation inputs. Each label
includes a pinned source permalink, location, source hash and reasoning. These
reviews exposed flow defects corrected after the baseline; they are not an accuracy
claim for the corrected implementation.

`scan-results.json`, `review-counts.json`, `review-candidates.json` and
`scan-provenance.json` record the latest installed-pack rerun. Its candidates are
explicitly **unreviewed**. Diagnostic excerpts are capped at ten per project.
Do not combine these candidates with baseline labels or treat coverage gaps as clean
analysis. Pinned checkouts are listed in `applications.json` and selected scopes in
`cases.json`.

Release 2.0.0 verification:

- 265 Maven tests passed, with one opt-in benchmark skipped; 16 script tests and
  ten Gradle tests passed. See the [release evidence](../../releases/2.0.0.md).
- The [published-download installation matrix](https://github.com/techbhaskar/null-guard/actions/runs/41341798456)
  passed on Linux x64, Windows x64 and macOS arm64. It downloads the public CLI and
  signed packs, verifies signatures, installs into empty pack homes, scans the
  seven-language example, runs behavioral tests and removes all packs.

Earlier implementation validation (preserved as historical evidence):

- Java 21 Maven verification: 243 tests, no failures/errors, one opt-in benchmark skipped.
- Existing Gradle integration and legacy CLI/Maven smoke checks pass.
- All five signed packs install into empty pack homes, scan the seven-language mixed
  example, execute 40 behavioral/review tests and remove successfully on macOS arm64
  and Linux arm64. Linux uses `scripts/qualification/Dockerfile`; only public source,
  dependencies and artifacts enter the container. Signing stays on the host.
- The [platform pack matrix](https://github.com/techbhaskar/null-guard/actions/runs/40641075666)
  and [JVM/Gradle pipeline](https://github.com/techbhaskar/null-guard/actions/runs/40641075702)
  passed. The pack matrix runs all 40 behavioral/review tests on each platform.
- Schema 2.0, official SARIF schema and dashboard script/visual checks pass.
- Repeat archive comparisons and tested environments are recorded separately.

Remaining qualification gates:

- Review the corrected implementation's findings against source evidence; baseline
  labels alone do not establish its accuracy. Keep every rule experimental meanwhile.

Version 2.0.0 is [published on GitHub](https://github.com/techbhaskar/null-guard/releases/tag/v2.0.0)
with the new analyzers explicitly experimental. Maven Central publication is
pending confirmation. Distribution verification does not establish analyzer accuracy.

Validate the frozen review with:

```sh
python3 scripts/review_multilang.py validate \
  docs/multilang/qualification/reviews-combined.json \
  --counts docs/multilang/qualification/reviewed-counts.json
```

Generate fresh, unlabeled candidates with `scripts/qualification/scan.py`. The
validator rejects missing labels and never fabricates precision results.
