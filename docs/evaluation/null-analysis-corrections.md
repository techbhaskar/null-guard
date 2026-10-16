# Null-analysis correction validation

Validated locally on a private Spring application on 2026-10-16.

Both baseline and candidate contain **5,329 internal methods**. The current source selection excludes generated, DTO and configuration paths; the earlier user report used a different selection. The baseline is published 1.0.1. The candidate includes the null-analysis fixes and concurrent OpenAPI/static-analysis additions, so this is a combined working-tree comparison rather than an isolated benchmark.

| Metric | Published baseline | Working-tree candidate |
| --- | ---: | ---: |
| NG001 | 20,983 | 13,238 |
| NG002 | 3,009 | 1,980 |
| NG004 | 11,224 | 1,469 |
| NG005 | 1,269 | 47 |
| Stability index | 54.27 (F) | 73.51 (C) |
| High-risk methods | 3,572 | 2,311 |

Counts are report entries, not distinct defects. No precision/recall claim follows from the reductions. Total method counts differ because external graph nodes change after call resolution corrections.

## Verification

- Maven `verify` passed on JDK 17: 112 tests, zero failures/errors, one benchmark skipped.
- Candidate CLI completed on JDK 21 with a 4 GB heap and an immutable copy of the assembled JAR.
- Guarded ternaries, pattern variables, catch parameters and required injected dependencies in the inspected audit method no longer warn.
- The inspected `containsKey` followed by `map.get(...).toString()` still warns: a present map key can contain a null value.
- Remaining logger dereference entries: 372. Further framework/inheritance review is still needed.
- Regression tests retain unsafe branches, optional injection/request bodies, unrelated factories and nullable Optional/map results.
- Candidate generated HTML, JSON, SARIF, DOT and OpenAPI outputs.

## Limits

The baseline wrote JSON/SARIF/DOT, but its Gradle HTML rendering ran out of heap; comparison uses its completed JSON. The baseline build directory was subsequently cleaned during concurrent development. Its summary/counts above were captured before that cleanup. CVE/static-analysis findings introduced by the concurrent work are excluded from the null-rule comparison and have not been independently triaged.

Unresolved calls stay external rather than inventing internal paths. This reduces false connections but can omit real dispatch paths. Repeated map accesses remain conservative, and explicit framework assumptions apply to normal managed execution. Continue report-only adoption.
