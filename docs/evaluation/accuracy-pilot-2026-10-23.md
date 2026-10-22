# Broader-corpus source-only pilot — 2026-10-23

No independent human review has occurred. No precision, recall or false-positive
rate is claimed. These runs exercise the current analyzer on two non-Spring
libraries and prepare review material.

Analyzer: source commit `0112f119236af11133d7fa46af007ad048684ad6`, with
documentation/review-tool/CI working-tree changes; no analysis-engine changes.
Packaged with `mvn --batch-mode -DskipTests package` (successful; Java tests
skipped). JAR SHA-256:
`dad5e227977b4cb5ef72ed1cb97da906b6a9d5538b1cc348b97c29d67429ecfc`.
Runtime: Oracle JDK 21.0.9, Windows, 2 GiB maximum heap per analysis.
No dependency classpath was supplied. Upstream checkouts were clean.

| Project | Pinned upstream commit | Result |
| --- | --- | --- |
| Gson | `e2292f9c9f2e07a4791827e8eea34aa7d80545d6` | Exit 0, report generated; completeness INCOMPLETE |
| Commons Lang | `b9b30efb01e861a16503232bb96b684107e73199` | Exit 1, StackOverflowError, no report |

Gson discovered/parsed 86 source files with zero reported source-file problems.
It reported 851 modeled and 157 unmodeled executables, 817 declared/analyzed
bodies, and 3,151 symbol-resolved calls among 3,174 observed call sites. These
counts are source-model accounting, not execution coverage or correctness rates.
Missing dependencies, reflection and unmodeled executables limit interpretation.

Direct null-safety finding counts:

| Rule | Count |
| --- | ---: |
| NG001 | 590 |
| NG002 | 157 |
| NG003 | 0 |
| NG004 | 262 |
| NG006 | 98 |
| Total eligible | 1,107 |

A uniform sample without replacement selects 100 of those findings using seed
20261022. Both review sheets start unreviewed. Suggestions and architecture
findings are excluded from the accuracy denominator. High warning volume could
reflect false positives, real risks or unsupported contracts; it is not evidence
for any one explanation until reviewed.

The Commons Lang stack trace reports `java.lang.StackOverflowError`, including
`JavaParserTypeDeclarationAdapter.solveMethod`,
`ClassOrInterfaceDeclarationContext.solveMethod` and
`MethodResolutionLogic.solveMethodInType` frames. Symbol-resolution recursion is
an observed failure location, not a proven minimized root cause. Preserve the
failure as an unsuccessful corpus member; never interpret it as zero findings.

Commands and reviewer instructions are in [accuracy-protocol.md](accuracy-protocol.md).
Local raw evidence is under `target/accuracy/` (runs manifest, pilot summary,
Gson reports and `review/` packet/sheets); console logs are
`target/accuracy-gson.log` and `target/accuracy-commons-lang.log`.
These build outputs are ignored by Git and must be archived before a clean.

Gson report SHA-256:
`6a335cff985d20f83b5942c010c31915669f762c5f1fd86e5361f44f6f8185a2`.
Commons Lang failure-log SHA-256:
`43450b772b62155c1abe6934a83de509da8f689721714971e04fe591f76c23f2`.

Next: independently review the frozen Gson sample; investigate/minimize the
Commons Lang crash; rerun with dependency contracts; then expand to pinned
Quarkus/Micronaut services and current Spring runs. Planned projects are not
completed evidence. Retain these runs as the pre-fix comparison.
