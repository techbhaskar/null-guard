# Accuracy follow-up — 2026-10-23

Commons Lang now completes instead of terminating on a JavaParser stack overflow.
This is recovery at the symbol-resolution boundary, not a fix for JavaParser's
underlying recursive resolution behavior. An expanded JVM stack trace located the
original failure at `JavaParserAstParser.resolveCallSites`, in `MethodCallExpr.resolve()`.

Method calls, object creation and explicit constructor delegation preserve failed
source calls as unresolved when resolution raises `StackOverflowError`. No target
is guessed. Completeness details include the reason, expression and exact call
line. Other VM errors still propagate. No null-safety warnings were suppressed.

| Source-only pilot | Result | Direct null-safety findings | Completeness |
| --- | --- | ---: | --- |
| Gson, `e2292f9c9f2e07a4791827e8eea34aa7d80545d6` | Exit 0 | 1,107 | INCOMPLETE |
| Commons Lang, `b9b30efb01e861a16503232bb96b684107e73199` | Exit 0 | 3,396 | INCOMPLETE |

Both checkouts were clean. Both used Oracle JDK 21.0.9, Windows, `-Xmx2g`, with
no dependency classpath. The frozen analyzer JAR SHA-256 is
`f70fa9f62b7197ab4f74dc00be8903004d19a697c193272f2a042079dc2ef5d7`.
Exact commands, source provenance and analyzer working-tree state are recorded in
`.accuracy-work/runs.json`. This ignored work directory survives Maven clean;
archive it when sharing evidence.

Commons Lang parsed 264/264 discovered files and analyzed 4,517/4,517 declared
bodies. It still reports 56 unmodeled executables and 71 calls whose resolution
overflowed. First recovered calls include inherited
`ReflectionDiffBuilder.isRegistered/register/unregister` calls in `DiffBuilder`
and overloaded `append` calls. The run took about 507 seconds in this locally
contended environment; this is not a performance guarantee or correctness proof.

## Agent-assisted Gson review

A concurrent clean build removed the earlier `target/accuracy/` artifacts.
The pinned checkouts were reconstructed and a replacement packet frozen from the
new Gson report. Its per-rule counts remain unchanged. This is a new sample,
not the original frozen sample.

A uniform sample without replacement selected 100 of 1,107 direct null-safety
findings, using seed 20261022. Source excerpts, constructors, assignments, guards
and relevant contracts were inspected. The source-based judgments are recorded in
[the review sheet](gson-agent-review-2026-10-23.csv), with provenance and hashes in
[the metadata](gson-agent-review-2026-10-23.json).

- 44 findings judged false positives by one agent-assisted review.
- 56 context-dependent judgments: supported caller/input contracts or feasible
  violating executions remain unresolved.
- No independent human consensus, confirmed runtime bug reproducers, recall
  measurement or population precision estimate.

44/100 is an agent's observed sample judgment, not independently measured product
accuracy. The other 56 are not presumed correct. Both human sheets remain
unreviewed and separate from agent labels.

Concrete correction candidates:

| Category | Example and evidence |
| --- | --- |
| Primitive arguments | NG004 in JsonWriter.endArray: EMPTY_ARRAY and its parameter are int. |
| Class literals | NG004 in GsonBuilder.createFactories: Long.class/Double.class are non-null. |
| Static qualifiers | NG006 in FormattingStyle: Objects is a static type qualifier, not a field. |
| Field invariants | NG001 in JsonArray.remove: both constructors assign final elements with new ArrayList. |
| Guarded fields | NG001 in JsonWriter.setFormattingStyle: assignment uses requireNonNull before dereference. |
| Fluent return contracts | NG001 on StringBuilder.append: append returns its non-null receiver. |
| Deferred execution | NG001 in ConstructorConstructor: a non-null catch parameter is reported at lambda creation. |

These categories need targeted fixes and regression reproducers. The current
change only addresses scan termination and makes unresolved resolution failures
visible; the findings remain in reports.

## Reproduction and validation

Check out the pinned revisions under `.accuracy-work/corpus/{project}`. Build the
analyzer and copy its packaged JAR to `.accuracy-work/analyzer.jar` before running:

```sh
java -Xmx2g -jar .accuracy-work/analyzer.jar .accuracy-work/corpus/gson/gson/src/main/java --output=.accuracy-work/reports/gson
java -Xmx2g -jar .accuracy-work/analyzer.jar .accuracy-work/corpus/commons-lang/src/main/java --output=.accuracy-work/reports/commons-lang
python scripts/record_accuracy_pilots.py --work-dir .accuracy-work
```

`gson-runs.json` contains only the Gson entry from `runs.json`. Its frozen study
packet is `.accuracy-work/gson-review/packet.json`. A separate, unreviewed
100-finding sample across both libraries is in `.accuracy-work/corpus-review/`.
Prepare fresh packets with `scripts/accuracy_review.py`; do not overwrite existing
packets or edit finding context to match later reports.

`mvn --batch-mode verify` passed across all 11 modules. The local Surefire reports
contain 179 tests, zero failures/errors and one skipped opt-in benchmark,
including other tests being developed concurrently in the shared workspace.
New regression tests cover overflow recovery with a healthy sibling call,
propagation of unrelated VM errors, and failure details in completeness reports.
The five Python review-workflow tests also pass.
The configured aggregate coverage gates passed (91.7% instructions, 78.3% branches
in this local shared-workspace run). Commons Lang's SARIF passed official-schema
validation using the validator's official GitHub mirror fallback. The evidence
archive is `.accuracy-work/pilot-evidence.zip` and includes the frozen JAR, reports,
review packets, logs and analyzer source patch.

Independent human review, dependency-complete reruns and fixes for the reviewed
false-positive categories remain necessary before recommending CI enforcement.
