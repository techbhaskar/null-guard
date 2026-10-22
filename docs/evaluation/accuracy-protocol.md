# Accuracy evidence protocol

Status: tooling prepared; independent human accuracy review has not been completed.
See [the initial pilot observations](accuracy-pilot-2026-10-23.md) for the completed
Gson run and Commons Lang analysis failure.
The older selected, agent-assisted labels in `finding-labels.csv` are historical
diagnostic evidence, not a random accuracy sample or independent ground truth.

## Corpus and scope

Start with direct null-safety rules NG001, NG002, NG003, NG004 and NG006.
Score architectural recommendations, contract drift and CVE heuristics separately;
their presence must not inflate the null-safety denominator.

| Project family | Purpose | Initial status |
| --- | --- | --- |
| Spring Petclinic and Spring guides | Framework contracts and injection | Historical runs; rerun current revision |
| Google Gson | Serialization, generics, reflection boundaries | New source-only pilot |
| Apache Commons Lang | Plain Java, contracts, complex utility control flow | New source-only pilot |
| Quarkus service | CDI and REST contracts | Planned; select representative module and pin commit |
| Micronaut service | Generated metadata and injection | Planned; select representative module and pin commit |

This is a purposive corpus, not a statistically representative sample of enterprise
Java. Report each project's results separately. A pooled sample is weighted by
finding volume and can be dominated by a large/noisy library. Add small-project
censuses as separately named studies rather than silently replacing sampled rows.

For every run retain repository URL, exact upstream commit, source roots/exclusions,
analyzer revision and JAR SHA-256, command, JDK, dependency classpath, raw JSON and
analysis completeness. Record dirty source/analyzer state. Use report-only mode.
Source-only runs without dependencies are explicitly limited; compare like-for-like
configurations and rerun with resolved dependencies before using findings for a
release decision. A successful process or a high stability score proves no accuracy.

## Prepare a review

To reproduce the initial library pilots, clone the repositories under
`target/accuracy-corpus/`, then check out these exact revisions:

- Gson: `e2292f9c9f2e07a4791827e8eea34aa7d80545d6`
- Commons Lang: `b9b30efb01e861a16503232bb96b684107e73199`

Build the analyzer with `mvn --batch-mode -DskipTests package` (packaging only;
this does not run the Java tests). Run:

```sh
java -Xmx2g -jar nullguard-cli/target/nullguard-cli-1.1.2-jar-with-dependencies.jar target/accuracy-corpus/gson/gson/src/main/java --output=target/accuracy/gson
java -Xmx2g -jar nullguard-cli/target/nullguard-cli-1.1.2-jar-with-dependencies.jar target/accuracy-corpus/commons-lang/src/main/java --output=target/accuracy/commons-lang
python scripts/record_accuracy_pilots.py
python scripts/accuracy_review.py prepare target/accuracy/runs.json target/accuracy/review --size 100 --seed 20261022
```

The recorder must run immediately after fresh successful analyses using that JAR,
without rebuilding or changing either checkout in between. It records current
provenance; it cannot reconstruct which binary produced a stale report. Preserve
`target/accuracy/` outside build output before running a Maven clean. The recorder
also stores raw per-rule counts and completeness details in `pilot-summary.json`;
these are execution observations, not accuracy measurements.

Create a JSON manifest, with report paths relative to the manifest:

```json
{
  "runs": [{
    "project": "library",
    "repository": "https://example.org/owner/library",
    "commit": "exact-upstream-commit",
    "analyzer_revision": "exact-nullguard-commit; describe dirty state",
    "analyzer_jar_sha256": "sha256-of-jar",
    "command": "exact command including source root and options",
    "classpath": "none (source-only pilot) or exact dependency classpath",
    "report": "library/nullguard-report-latest.json"
  }]
}
```

```sh
python scripts/accuracy_review.py prepare target/accuracy/runs.json target/accuracy/review --size 100 --seed 20261022
```

Omit `--size` for a full census. Freeze the packet before inspecting findings.
Preserve the manifest, report and packet together. Each packet records report
hashes and source provenance, includes completeness metadata, and creates separate
`review-a.csv` and `review-b.csv` files. Never edit finding IDs or context columns.

Give the sheets to two human reviewers who inspect the pinned source and contracts
separately, without seeing each other's labels. Each records a stable reviewer ID,
label and concrete evidence (source path/line, relevant contract, feasible input or
test). Record an independence attestation and any maintainer/author involvement
alongside the study. The script checks distinct IDs; it cannot certify human
identity, expertise or independence. Agent assistance must be disclosed and cannot
be presented as independent human review.

Labels:

- `true_positive`: the specific warning is supported by a feasible execution or
  violated contract. Prefer a minimal reproducer; distinguish feasibility from an
  observed production incident.
- `false_positive`: the specific warning is contradicted by source/control-flow
  or an applicable documented contract. Lack of a known crash is insufficient.
- `context_dependent`: external assumptions or runtime configuration prevent a
  defensible decision from the available evidence.
- `unreviewed`: no completed judgment. Never convert this to a negative result.

Disagreements stay visible. Adjudicate them with a third human and retain the
original sheets and rationale in a separate adjudication log. Do not overwrite the
two original judgments to make agreement appear higher.

```sh
python scripts/accuracy_review.py summarize target/accuracy/review/packet.json target/accuracy/review/review-a.csv target/accuracy/review/review-b.csv
```

## What can be claimed

The summary shows counts by project and rule, unreviewed entries, context-dependent
judgments and disagreements. It reports TP/(TP+FP) and FP/(TP+FP) only among
decisive agreements, with that restricted denominator in the metric names.
These descriptive ratios can be biased when unresolved cases are excluded.
Always publish the selected and eligible counts and every unresolved count beside
the ratios. Zero decisive agreements produces null metrics, not perfect accuracy.

FP/(TP+FP) is a false-discovery proportion among emitted warnings. It is **not**
the statistical false-positive rate FP/(FP+TN): unreported safe sites have not been
labeled. Recall also requires independently identified bugs, including missed ones.
Use a separate, held-out set of documented real bug/fix pairs or injected faults
with explicit expected locations to measure detection; synthetic results must stay
separate from real-project precision. Do not infer accuracy from fewer warnings.

Before claiming readiness for an enforcement policy: complete the current corpus,
resolve dependency/completeness limitations, publish human-reviewed evidence per
rule/project and regression reproducers for confirmed mistakes, and agree acceptance
targets before looking at results. Reserve additional projects for validation after
fixes, so the same examples are not both training evidence and independent proof.
