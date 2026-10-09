# NullGuard: current implementation contract

Updated: 2026-10-09. Artifact version: `1.0-SNAPSHOT`. This document describes implemented behavior; the older v1.0 document is a historical design proposal.

## Purpose and scope

NullGuard analyzes Java source without running the target application. It combines CFG-based null-state analysis, resolved call relationships, boundary-contract checks, API reach, risk propagation, suggestions, and report exports. It remains an internal pilot pending validation on representative real services and release hardening.

## Execution order

1. Parse Java classes/interfaces, method signatures, parameter declarations, nullability annotations, source ranges, and semantic call sites.
2. Build the global call graph using resolved targets where available and name/arity fallback otherwise.
3. Iterate method return summaries across resolved calls. Run CFG data flow with parameter entry states and known callee return states.
4. Check declared non-null returns, unchecked parameters, and nullable arguments passed to non-null or unguarded callee parameters.
5. Discover annotated API entry points, traverse outgoing calls, and attach reverse API reach counts.
6. Propagate numeric risk over the call graph to a bounded fixpoint and attach adjusted method risk.
7. Detect hotspots using the final adjusted risks. Compute endpoint risk as the maximum adjusted risk among its reachable methods.
8. Compute the stability summary and ranked suggestions.
9. Export the graph, shared findings, JSON, DOT, and SARIF. The Maven entry point also writes HTML.
10. Validate pipeline artifact presence and record call-cycle warnings.
11. Apply the CLI/Maven failure policy after writing reports.

Hotspot detection must run after step 6. Reading previously calculated hotspots is insufficient because adjusted risks are unavailable before propagation.

## Core model and identities

`ProjectModel -> ModuleModel -> PackageModel -> ClassModel -> MethodModel` contains source descriptors and typed analysis artifact views. Source descriptors are immutable; the analysis passes attach summaries, contracts, null-state models, risk, and API reach. Execution is sequential and engine instances should not be shared across concurrent invocations.

Every cross-module method key uses `MethodIds.of(package, class, method)`, including nested qualified types, for example `sample.Services.Risky#load()`.

`SourceLocation` stores source-root-relative paths and one-based inclusive start/end coordinates. CFG statement ranges come from JavaParser when available. Findings without an exact statement range use the statement line with column 1; method-level findings and suggestions use the declaration range. SARIF converts inclusive end columns to exclusive end columns.

## Null-state and contract semantics

The lattice contains `NULL`, `NON_NULL`, and `UNKNOWN`. Reference parameters begin unknown unless annotated `@NonNull`, `@NotNull`, or `@Nonnull`; primitive parameters begin non-null. `@Nullable` and `@CheckForNull` returns remain potentially nullable. Annotation matching uses simple annotation names.

CFG joins and null-condition branches refine state. Early returns and throws protect downstream code. `Objects.requireNonNull` establishes non-null state on surviving paths. Assignments retain nullability from local aliases and resolved return summaries. Unknown dependency returns remain conservative.

Method summaries expose parameter entry nullability and whether any return can be null. Primitive returns are non-null on surviving paths; bodyless declarations do not acquire proven summaries from empty CFGs. Return-summary iteration follows resolved in-project calls. Recursive or unresolved reference returns remain unknown where non-nullness cannot be proven. A method returning null is not automatically a contract violation: NG003 requires a declared non-null return. Nullable returns still contribute intrinsic risk and may generate strengthening suggestions.

NG002 identifies an unannotated reference parameter dereferenced without a guard. NG004 checks caller arguments against declared non-null or inferred unguarded callee parameters using CFG state at the call. Resolved target metadata is preferred, with call-graph candidates as a conservative fallback. Overloads sharing a written name and arity can be merged conservatively; runtime dependency injection and dispatch precision are not guaranteed.

## API analysis

Methods require common Spring MVC or JAX-RS mapping annotations. Private/protected methods are excluded. Controller/resource class names alone do not create endpoints. Method-level Spring mappings and JAX-RS `@Path` values and HTTP verbs are recognized; complex mappings and class-level path composition are not complete.

The propagation chain is a deterministic bounded DFS reachability list, not an enumeration of independent paths through every branch. Reverse reach counts distinct API entries per method. Endpoint risk is the maximum adjusted method risk in that list. Endpoints reaching a hotspot carry an `ARCHITECTURAL_HOTSPOT` indicator. The legacy hybrid API-risk helper is not the active endpoint scoring formula.

## Risk and gates

Intrinsic score adds 20 per unguarded dereference, 10 for a null-capable return, and 15 for a null-propagation flag, clamped to 0–100. Numeric propagation uses caller/callee relationships, decay, convergence threshold, and iteration limits. API exposure and contract penalties are added before the final 0–100 clamp.

Stability index is `clamp(100 - average adjusted risk, 0, 100)`. Grades are A at 90+, B at 80+, C at 70+, D at 60+, otherwise F. An empty analysis yields `N/A`; it is not a clean-code guarantee.

Hotspot candidates require adjusted risk >= 70 and distinct API reach >= 5 by default. The risk threshold is expressed in score points on the 0–100 scale, not a 0–1 fraction. Hotspot score is `adjustedRisk * ln(1 + reach)` and can exceed 100. Severity is LOW below 40, MODERATE at 40+, HIGH at 60+, CRITICAL at 80+.

The fail policy gates hotspot severity, not the stability index. With `failBuild=false` the same findings are reported without policy failure. CLI policy failure returns 1; Maven throws `MojoFailureException`. Invalid severity values are rejected. A no-data report does not currently automatically fail the build; consumers must check availability.

## Configuration

There is no YAML configuration loader. Use Maven properties/plugin configuration, CLI flags, or the `NullGuardConfig` builder.

| Setting | Default |
| --- | --- |
| scoring decay | 0.85, valid range [0,1) |
| external penalty multiplier | 1.2 |
| convergence threshold | 0.001 |
| maximum scoring iterations | 100 |
| high-risk method threshold | 60 |
| hotspot risk threshold | 70 points |
| hotspot API reach threshold | 5 |
| API traversal depth limit | 10 |
| fail build | false |
| failing hotspot severity | CRITICAL |

Hotspot risk/reach and traversal depth are currently builder settings, not public Maven/CLI options. Maven supplies compile source roots/classpath; CLI accepts `--classpath` with the platform path separator. The parser scans the selected source directory; extra roots assist resolution rather than automatically adding all their files to the analysis.

## Findings and output contract

JSON has `schemaVersion: "1.0"`, `summary`, `graph`, `findings`, `apiEndpoints`, `hotspots`, and `suggestions`. Each finding contains stable rule ID, SARIF-style level, method ID, message, and source location where source is available. External-node suggestions may have no source location.

| Rule | Meaning |
| --- | --- |
| NG001 | Possible null dereference |
| NG002 | Unchecked nullable parameter |
| NG003 | Declared non-null return violation |
| NG004 | Nullable caller argument violates callee requirements |
| NG005 | Architectural hotspot |
| NG101 | Add null guard suggestion |
| NG102 | Strengthen contract suggestion |
| NG103 | Validate external return suggestion |
| NG104 | Refactor blast radius suggestion |
| NG105 | Break risk chain suggestion |

SARIF 2.1.0 shares the same findings and rule catalog. Artifact paths are relative to `SOURCE_ROOT`, whose URI points to the analyzed root. Consumers moving reports to a different machine may need to remap that base. CLI and Maven write `nullguard-results.sarif`. Maven also writes timestamped/latest HTML, JSON, and DOT. CLI writes latest JSON and DOT only. Reports are written before enforcing a hotspot gate.

## Verification and benchmarks

`mvn verify` runs module tests plus pipeline, actual CLI entry-point, and actual Mojo execution tests. The checked-in `examples/orders-service` fixture connects five annotated API methods to a nested service and risky repository and includes guarded/unguarded contract examples. Its annotations are stand-ins for source testing, not a running Spring application. Tests exercise canonical IDs, reach, endpoint risk, hotspots, contract diagnostics, report locations, SARIF fields, report-only mode, and failing build policy.

JaCoCo produces per-module HTML/XML coverage measurements under `target/site/jacoco`. The auxiliary `nullguard-coverage` module combines all execution data under `target/site/jacoco-aggregate`, including upstream modules exercised by integration tests. CI enforces at least 80% aggregate instruction coverage and 60% branch coverage with `scripts/summarize_coverage.py`; Maven itself measures coverage without invoking this Python gate. GitHub Actions defines JDK 17/21 Linux/Windows builds, tests the packaged CLI and Maven-injected goal, validates SARIF against the OASIS schema, and archives reports; these hosted matrix runs require pushing the workflow.

`mvn verify -Dnullguard.benchmark=true` runs an opt-in workload of 10, 100, and 500 methods. Each size has warmup and three measured full pipeline runs. `nullguard-maven-plugin/target/benchmark.json` records median time, samples, sampled used heap, Java/OS, and CPU count. This is a reproducible smoke benchmark, not a peak-memory profiler or evidence for 50k-method support.

## Remaining work

Real-framework fixtures and metadata, interface/proxy precision, annotation package disambiguation, general expression semantics, multi-module analysis aggregation, incremental caching, baseline/diff gates, robust incomplete-analysis policies, published artifacts, security/release policies, and larger benchmark corpora remain work items. CFG exception handling is conservative and finally blocks are not duplicated over every abrupt exit.

OpenAPI generation, Swagger UI, and the unified developer portal are the next product phase. They are not implemented by this hardening pass.
