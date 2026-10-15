# Real-project review — 2026-10-16

## Publication verified

Published 1.0.0 resolved and analyzed a separate project with
`-Dmaven.repo.local=/tmp/nullguard-central-fresh-20261016` (initially empty).
Maven reported BUILD SUCCESS. The plugin POM also returns HTTP 200 from Central.
The cold-cache log is locally available at `/tmp/nullguard-central-fresh.log`.
No private publishing settings or locally installed NullGuard snapshot was used.

## Comparison

Both versions ran the Maven analyze goal in report-only mode with the same source
and dependency configuration. Baseline is published 1.0.0; candidate is locally
built 1.0.1-SNAPSHOT. All six analyses completed successfully.

| Project | Baseline findings | Candidate findings | New findings |
| --- | ---: | ---: | ---: |
| Spring Petclinic | 177 | 161 | 0 |
| Spring REST service guide | 2 | 0 | 0 |
| Spring Data JPA guide | 9 | 9 | 0 |

Pinned upstream commits and repository URLs are in [comparison.json](comparison.json).
The guide projects are small teaching applications, not representative production
services. This is three projects in one framework ecosystem, not broad validation.
Raw local logs and HTML/JSON/SARIF reports: `/tmp/nullguard-real-evaluation/`.

## Labeled review

[finding-labels.csv](finding-labels.csv) contains all 188 baseline report entries,
including notes/suggestions, their source locations, candidate status and evidence.
31 selected entries were reviewed: 21 false positives, 1 potential true positive,
and 9 context-dependent entries. The remaining 157 are explicitly unreviewed.
This was an agent-assisted source review, not independent human ground truth.
Do not calculate precision or recall from this selected sample.

The 18 removed entries include direct warnings and downstream risk recommendations;
18 fewer findings does not mean 18 distinct bugs were fixed.

Reviewed examples:

- Petclinic `Owner.getPets()` and `Pet.getVisits()` return initialized final collections;
  their direct call-site null warnings disappear.
- Spring `RuntimeHints.resources()` / `reflection()` have non-null API contracts;
  their direct null-return warnings disappear when the symbol resolves correctly.
- The REST guide's initialized final counter and string template no longer warn.
- Petclinic `Owner.addVisit` asserts its `pet` value is non-null before dereference.
  That false positive remains: Spring assertion postconditions are not yet modeled.
- Spring Data collection-return queries cannot return null; two such warnings
  remain in the JPA guide because repository-specific contracts are not implemented.
- The JPA guide's custom `Customer findById(long)` can return null. Its unchecked
  `customer.toString()` is a potential risk if no row matches. Seeded demo data
  does not prove it can never fail; no runtime failure was demonstrated.
- The JPA `demo` method returns a non-null lambda, but still attracts a null-return
  recommendation. Deferred lambda analysis and location granularity need work.

## Implemented scope and regression checks

Final fields initialized directly with new objects, arrays or non-null literals
are seeded as non-null in method entry states. `this.field` is distinguished from
shadowing parameters. Mutable fields and unknown factory results remain unknown.

Source Spring `@NonNullApi` and JSpecify `@NullMarked` package/class/method defaults
are recognized. Explicit nullable declarations and `@NullUnmarked` override these
assumptions. Package defaults do not leak to unrelated packages; generic type
variables remain conservative. Root type annotations are checked without treating
nullable generic elements as a nullable container. Module defaults, inherited
binary annotations and collection element nullness remain unsupported.

A narrowly scoped catalog recognizes resolved RuntimeHints zero-argument accessors.
It never matches an arbitrary application method merely named `resources`.
The catalog is used only when there is no analyzed source target, so source
implementations are analyzed rather than blindly trusted.

Regression tests cover initialized/null/mutable fields, field shadowing, JSpecify
nullable/unmarked overrides, Spring defaults, package isolation, generic type
variables, and resolved-framework versus similarly named application methods.

## Repeat the evaluation

Build the candidate with `mvn clean install`, clone the pinned repositories, then:

```sh
python3 scripts/evaluate_projects.py \
  --project petclinic=/path/to/spring-petclinic \
  --project rest-service=/path/to/gs-rest-service/complete \
  --project data-jpa=/path/to/gs-accessing-data-jpa/complete
```

Use JDK 17 and Maven on PATH. Reports go to ignored `target/evaluation/`.
The script compares outputs; it does not assign ground-truth labels or infer
correctness from falling warning counts. Re-review labels after source changes.

## Recommendation

Continue report-only adoption. Real developer sessions and broader independent
precision/recall evidence are still required before recommending CI enforcement.
Volunteer recruitment and observation materials are in [the usability study](../usability/README.md).

Primary contract references:

- https://jspecify.dev/docs/spec/
- https://docs.spring.io/spring-framework/docs/6.2.12/javadoc-api/org/springframework/lang/NonNullApi.html
- https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/aot/hint/RuntimeHints.html
- https://docs.spring.io/spring-data/commons/reference/repositories/null-handling.html

## Verification of this implementation

Full reactor build: 97 tests passed, 1 benchmark skipped. CLI/Maven smoke checks
and SARIF schema validation passed. Coverage gate passed with 82.6% instruction
and 65.2% branch coverage. The comparison script was also executed against the
REST sample, reproducing 2 baseline findings and 0 candidate findings.
The snapshot has not been published.
