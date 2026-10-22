# NG006: resolved static method qualifiers

The local lifecycle analyzer previously treated `Objects`, `Arrays` and
`Collections` as possible fields when evaluating static calls in a tracked
constructor or method. This produced NG006 warnings such as a null-field
dereference at `Objects.requireNonNull(...)`.

Resolved call targets now retain an explicit `staticMethod` flag from JavaParser's
resolved declaration. Lifecycle analysis omits the receiver-dereference warning
only when that flag is established. It still evaluates qualifier expressions and
arguments, so nested instance dereferences remain visible. Unresolved calls and
older/manual call targets remain conservative. Private instance calls are not
mistaken for static calls merely because they do not use virtual dispatch.

The existing four-, five- and six-argument target constructors are retained.
They default the new flag to false; capitalization and familiar method names
never establish a static call.

## Regression evidence

`StaticQualifierLifecycleTest` failed before the fix on the reported
`Objects.requireNonNull`, `Arrays.asList` and `Collections.emptyList` false
positives. It passes after the fix and also checks:

- A nullable field named `Objects` invoking a private instance method still warns.
- A nested instance call on a null field in a static qualifier still warns.
- A static call written through a null instance does not report a receiver
  dereference, matching Java's static invocation semantics.
- Fully qualified JDK static calls do not produce NG006 receiver warnings.

Existing constructor/lifecycle and symbol-resolution regression suites pass.
Full `mvn --batch-mode verify` passed across all 11 modules: 188 tests,
zero failures/errors and one skipped opt-in benchmark. Nine accuracy-workflow
Python tests passed. The configured aggregate coverage gates passed
(92.1% instructions and 79.4% branches in this local run).

## Individual finding comparison

`scripts/compare_accuracy_reports.py` compares findings by rule, method, message,
severity and source location, retaining duplicate counts. It writes all added and
removed entries plus report SHA-256 hashes. Four tests cover duplicate removal,
location/message changes, severity changes and missing completeness metadata.

```sh
python scripts/compare_accuracy_reports.py baseline.json candidate.json --output comparison.json
```

Use the same pinned sources and classpath for both runs. Keep the earlier human
review sheets and the agent-assisted triage separate from these differences.
Fewer findings do not establish precision, recall or independent human agreement.

## Gson replay

Pinned source revision: `e2292f9c9f2e07a4791827e8eea34aa7d80545d6`, clean checkout.
Both runs use JDK 21.0.9, `-Xmx2g`, the same main Java source root and no dependency
classpath. Candidate JAR SHA-256:
`b4c5346431fb3d9fbc7ef86d2e686e4641561ee0adfca93fadf09a93172b3796`.
The preserved baseline JAR SHA-256 is
`f70fa9f62b7197ab4f74dc00be8903004d19a697c193272f2a042079dc2ef5d7`.

NG006 findings fell from 98 to 10: 88 individual warnings removed, zero added.
All 11 NG006 findings judged false positives in the frozen agent-assisted sample
disappeared. No findings from other rules changed, including severity/location.
Both reports remain `INCOMPLETE`. Remaining lifecycle warnings concern instance
or array fields and have not been adjudicated by this change.

Raw comparison: `.accuracy-work/static-qualifier/gson-comparison.json`.
Candidate report: `.accuracy-work/static-qualifier/reports/gson/nullguard-report-latest.json`.
The frozen review sheet is unchanged; this is a targeted regression result,
not a new independent accuracy estimate.

## Commons Lang replay and combined result

Pinned source revision: `b9b30efb01e861a16503232bb96b684107e73199`, clean checkout,
with the same JDK, heap limit and source-only configuration as the baseline.
NG006 findings fell from 222 to 58: 164 individual warnings removed, zero added.
No other rule's findings changed, including severity/location. The report remains
`INCOMPLETE`, retaining the same 71 explicit resolver-overflow limitations.

| Project | Baseline NG006 | Candidate NG006 | Removed | Added |
| --- | ---: | ---: | ---: | ---: |
| Gson | 98 | 10 | 88 | 0 |
| Commons Lang | 222 | 58 | 164 | 0 |
| Total | 320 | 68 | 252 | 0 |

Both candidate SARIF reports passed official-schema validation. Per-run source
revisions, commands, hashes and the 11 matched frozen review IDs are recorded in
[static-qualifier-results.json](static-qualifier-results.json). Full finding-level
comparisons are retained under `.accuracy-work/static-qualifier/`, along with
the frozen candidate binary and logs. `evidence.zip` in that directory packages
the candidate, source patch, baseline/candidate reports and regression evidence.

These projects informed this fix and are regression evidence, not a held-out
accuracy study. Independent human review and fresh-project validation remain
pending. The next separate correction is NG004 warnings on primitive arguments
and class literals; nullable boxed values that may be unboxed must stay visible.
