# Correctness fixes and analysis completeness (unreleased)

These are working-tree changes after the published 1.1.1 release. They do not
modify artifacts already available from Maven Central.

## Matching and constructor contracts

Source-linked method IDs retain their existing spelling. A separate resolved
signature is used to match parameter types, including qualified names and generic
ancestor substitutions. The active graph builder never guesses internal targets
for unresolved calls. Static/private methods are not virtual override candidates;
absence of a signature match does not broaden matching to same-arity overloads.

Explicit constructors are modeled as `<init>(...)` executables with CFGs,
source locations and contract analysis. Object creation and explicit `this(...)` /
`super(...)` delegation are checked for nullable arguments (NG004). Constructor
parameters that directly initialize a framework-assumed required field must
uphold that non-null assumption. Explicit nullable parameters remain nullable.
Safe values and guarded arguments must not generate NG004 just because another
constructor overload requires non-null input.

This is not general object-sensitive heap analysis. Generated Lombok constructors,
implicit constructors, field/static initializer execution and manually created
field-injected objects are not fully modeled. A non-null framework assumption
is not proof that every possible object was initialized by a framework.

## Report and dashboard

The JSON report adds `analysisCompleteness`; the Overview displays the same
information separately from the stability, architecture and security scores.

- Source files discovered/parsed, source problem paths, and unmodeled executables.
- Declared versus successfully analyzed bodies, CFG failures and analysis failures.
- Total and symbol-resolved call-site counts plus unresolved-call details.
- Resolved dependency calls (without body analysis), accessor calls intentionally
  excluded from the architectural graph, and source dispatch with no modeled body.
- Framework non-null assumptions with owning method IDs and source locations.
- Explicit runtime, dependency-body and independent-module scope limitations.

`INCOMPLETE` identifies known parser, executable-modeling, CFG or analysis gaps.
`LIMITED` identifies unresolved calls, missing source dispatch bodies, excluded
accessors or framework assumptions. `SOURCE_ANALYZED`
means only that these particular source checks found no counted gaps; it never
means runtime-complete, production-safe or free of defects. No-source and
unavailable-accounting cases are reported separately.

Counts are scoped to supplied sources and modeled call sites, not execution
coverage percentages. A resolved dependency call does not mean its body was
analyzed; absence of an unresolved call does not prove proxy/reflection coverage.
Parser recovery with a partial AST is counted as a source problem, even when
some methods can still be analyzed.

Report-only adoption remains recommended. This work does not add new gating
policies, baselines, suppressions, multi-module aggregation, JFR or an agent.

## Verification (2026-10-22)

`mvn --batch-mode "-Djacoco.append=false" verify` succeeded for all 11 modules:
130 tests passed, zero failures/errors, and one opt-in benchmark skipped.
Aggregate JaCoCo coverage was 86.8% instructions and 68.8% branches, passing the
80%/60% thresholds. The 50,000-method benchmark was not rerun.

Regression tests cover qualified parameter spellings, generic interface
substitution, static method hiding, direct `super` dispatch, constructor bodies,
nullable/safe/guarded constructor arguments, overload isolation and explicit
`this`/`super` delegation. Completeness checks cover parser problems, unmodeled
record methods, missing analysis artifacts, unresolved calls, dependency bodies,
excluded accessors and empty/unavailable accounting. CLI and actual Maven Mojo
tests verify that the metadata reaches the generated reports.

A packaged CLI reproducer confirmed the exact two originally reported failures
are corrected. Four OpenAPI regression fixtures and the generated sample spec
passed independent validation; the sample SARIF passed the official schema.
The dashboard was inspected in a browser, including expanding the unresolved-call
and framework-assumption details and viewing the NG004 constructor finding.
