# NG004: primitive arguments and class literals

NG004 previously reported nullable arguments for primitive fields, primitive
method results, primitive array elements and class literals. Contract analysis
reparses control-flow statements without the original symbol solver, so source
type information was unavailable at the point where those arguments were checked.

Source call sites now retain argument indexes whose actual expressions resolve
to primitive types. Contract checks use a fact only when all source sites with
the same printed call agree on that argument and the resolved callee. Unresolved
or ambiguous facts retain the conservative warning. This applies to method calls,
object creation and explicit constructor delegation. Older manually constructed
call-site models default to no primitive facts.

The proof is based on the argument expression, not on a primitive destination
parameter: an Integer argument may still be null when passed to an int parameter.
Facts are limited to names, field accesses, method results and array elements.
Casts, conditionals and assignments do not acquire a blanket primitive exemption,
preserving their existing nullable-unboxing warnings. Nullable receivers of
explicit unboxing methods remain eligible for NG001.

Class literals (including primitive, array and void class literals) are recognized
as non-null by the shared expression evaluator. This is a Java expression fact
and can improve method summaries and downstream warnings as well as NG004.

## Regression checks

`PrimitiveArgumentContractTest` reproduces the original incorrect warnings and
checks primitive fields, String.length(), char array elements, class literals,
boxing to a non-null Integer parameter, object construction and this/super
delegation. Negative checks retain warnings for nullable Integer arguments,
nullable fields, explicit casts, conditional/assignment unboxing, nullable Class
arguments and constructor arguments. A nullable explicit intValue() receiver
still produces NG001.

The earlier NG006 correction and its regression evidence remain separate in
[static-qualifier-fix.md](static-qualifier-fix.md). The frozen human review sheets
and single-agent triage are not relabeled by these changes. Counts from regression
projects do not establish independent human precision or recall.

## Validation and Gson replay

Full `mvn --batch-mode verify` passed across all 11 modules: 189 tests,
zero failures/errors and one skipped opt-in benchmark. All nine Python accuracy
workflow tests passed. Aggregate coverage gates passed (88.8% instructions,
77.9% branches in this local run).

The baseline is the preceding NG006-fixed binary, SHA-256
`b4c5346431fb3d9fbc7ef86d2e686e4641561ee0adfca93fadf09a93172b3796`.
Candidate SHA-256:
`f49db1ce2670d9aef38babfd14e446130e3a701ec7d9018de6a674de387994df`.
Both runs use the same pinned clean sources, JDK 21.0.9, `-Xmx2g` and no dependency
classpath. Baseline files remain under `.accuracy-work/static-qualifier/`;
candidate evidence is under `.accuracy-work/primitive-arguments/`.

On Gson revision `e2292f9c9f2e07a4791827e8eea34aa7d80545d6`, NG004 findings
fell from 262 to 140: 122 removed, zero added. Fifteen of the 18 NG004 findings
judged false positives in the frozen agent-assisted sample disappeared.
The three remaining reviewed cases involve prior-dereference facts, reflected
field invariants, and a primitive-looking call inside a synthesized anonymous
body whose type proof remains unavailable. They are retained conservatively.

One NG105 remediation note for JsonWriter.beforeValue also disappeared. Its
contract penalty fell from 5 to 0 and adjusted risk from approximately 60.93
(HIGH) to 55.93 (MEDIUM); intrinsic and propagated risks were unchanged. This is
a verified consequence of the corrected contracts, not a newly fixed independent
bug. No other Gson findings changed, and no findings were added.

Gson's file/body/call accounting is unchanged: 86 discovered/parsed files,
817 declared/analyzed bodies and 3,151 symbol-resolved calls out of 3,174 call
sites. Completeness remains INCOMPLETE. Raw individual changes are in
`.accuracy-work/primitive-arguments/gson-comparison.json`.

## Commons Lang replay and combined results

On clean pinned Commons Lang revision `b9b30efb01e861a16503232bb96b684107e73199`,
NG004 findings fell from 1,126 to 502: 624 removed and zero added.
Two NG101 return-value remediation notes also disappeared, for ArrayCollector
finisher() in the old and new Streams packages. In each case contract penalty
fell from 5 to 0, and adjusted risk fell from 60.3125 to 55.3125, below the
recommendation threshold. Intrinsic and propagated risks were unchanged.
These are derived scoring changes, not two independently fixed runtime defects.

| Project | Baseline NG004 | Candidate NG004 | NG004 removed | Findings added |
| --- | ---: | ---: | ---: | ---: |
| Gson | 262 | 140 | 122 | 0 |
| Commons Lang | 1,126 | 502 | 624 | 0 |
| Total | 1,388 | 642 | 746 | 0 |

No other findings changed beyond those 746 NG004 warnings and three remediation
notes. In particular NG001, NG002 and NG006 findings are unchanged. The earlier
NG006 improvements remain intact at 10 findings in Gson and 58 in Commons Lang.

Commons Lang source accounting is also unchanged: 264 discovered/parsed files,
4,517 declared/analyzed bodies and 10,376 symbol-resolved calls out of 10,932.
Completeness remains INCOMPLETE, with the same 71 explicit resolver-overflow
limitations. Both candidate SARIF reports passed official-schema validation.

Detailed hashes, commands, source revisions, per-rule differences and matched
review IDs are in [primitive-argument-results.json](primitive-argument-results.json).
Full individual comparisons, frozen JAR and logs are retained under
`.accuracy-work/primitive-arguments/`; `evidence.zip` there packages those artifacts
with the baseline reports and source patch. Working-tree status in the provenance
is observed at evidence collection; the frozen JAR hash identifies the tested
candidate, independently of concurrent edits made after its build.

These are targeted regression results on projects used to investigate the issue.
Independent human review and fresh-project accuracy validation remain pending.
