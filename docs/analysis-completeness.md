# Correctness fixes and analysis completeness (1.1.2)

These changes are included in the GitHub source/tag release 1.1.2 after the
published 1.1.1 release. Version 1.1.2 is not yet published to Maven Central;
these changes do not modify artifacts already available there.

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

Constructor lowering now includes implicit no-argument constructors and common
Lombok `@RequiredArgsConstructor`, `@AllArgsConstructor`, `@NoArgsConstructor`
(including `force=true`) and constructor generation from `@Data`. Lowering is
in-memory only; no source files or annotation processors are executed/modified.
Explicit constructors remain authoritative for `@Data`, and duplicate signatures
are not added. Builder/value generation and custom constructor options remain
explicit completeness limitations rather than claimed support.

Field initializers and instance initializer blocks are lowered in declaration
order, after explicit `super(...)` and before the constructor body. Delegating
`this(...)` constructors do not run these initializers a second time. Field names
are qualified to avoid capturing constructor parameters. Static initializers are
analyzed in a collision-safe synthetic `$nullguard$clinit...()` executable with source locations.

Bounded local object analysis tracks allocations, aliases, direct field writes,
resolved constructor calls and method mutations. `NG006` reports potential null
field dereferences for these tracked instances, including manually created
field-injected beans. Unresolved/escaping calls invalidate field facts. Paths,
call depth (4), live paths (8) and statement work (300 per root) are capped; unsupported effects are reported in completeness
metadata. This is not whole-program, concurrent or general nested-heap analysis.

Static/private source validators can establish a normal-completion non-null
effect when a supported unconditional null rejection is proven from their body.
Checks swallowed by a catch, conditional checks and unresolved or virtual helper
dispatch do not establish this contract. JDK `requireNonNull` contracts use the
resolved `java.util.Objects` owner, not a familiar class/method name.
Supported helper effects propagate for at most eight proof passes; unsupported
validation bodies remain unknown. Annotation imports must match the actual
annotation name, so Lombok or Spring imports cannot transfer contracts to an
unrelated annotated class in the same file.

`NG006` is a source-linked warning, not proof of a runtime failure. It does not
independently change the existing stability score or Maven/CLI failure policy.
Runtime profiles, dynamic class loading, reflection and generated proxy behavior
remain outside static lifecycle coverage. No agent or JFR import was introduced.

## Report and dashboard

The JSON report adds `analysisCompleteness`; the Overview displays the same
information separately from the stability, architecture and security scores.

- Source files discovered/parsed, source problem paths, and unmodeled executables.
- Declared versus successfully analyzed bodies, CFG failures and analysis failures.
- Total and symbol-resolved call-site counts plus unresolved-call details.
- Resolved dependency calls (without body analysis), accessor calls intentionally
  excluded from the architectural graph, and source dispatch with no modeled body.
- Framework non-null assumptions with owning method IDs and source locations.
- Generated-executable provenance, construction-model gaps and local lifecycle
  path/depth/unsupported-effect limitations.
- Explicit runtime, dependency-body and independent-module scope limitations.

`INCOMPLETE` identifies known parser, executable-modeling, CFG or analysis gaps.
`LIMITED` identifies unresolved calls, missing source dispatch bodies, excluded
accessors, lifecycle/construction limits or framework assumptions. `SOURCE_ANALYZED`
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

## Earlier verification pass (2026-10-22)

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

## Constructor/lifecycle extension verification (2026-10-22)

The final full `mvn --batch-mode "-Djacoco.append=false" verify` passed for all
11 modules: 142 tests passed, zero failures/errors, one opt-in benchmark skipped.
Aggregate coverage was 87.1% instructions and 69.9% branches (80%/60% thresholds).
Twelve dedicated lifecycle regressions exercise generated constructors,
initializer order and parameter scope, delegation, allocation/alias separation,
mutations, manual field injection, source validation helpers, swallowed/fake
validation, original source lines, synthetic-name collisions and mixed annotation
imports. Every fixture also verifies that application source files remain intact.

A rebuilt standalone CLI analyzed a combined Lombok/Spring/local-allocation
fixture. Automated output assertions verified NG004 for an invalid generated
constructor call and NG006 for manual field injection and mutation through an
alias, without NG006 on the safe allocation or NG001 after proven validation.
Its dashboard, JSON, OpenAPI and SARIF were generated; the OpenAPI document and
SARIF passed independent schema validation. No credentials or runtime data were
used. A separate 10/100-method benchmark sanity run passed during this extension;
the historical 50,000-method benchmark was not rerun.
