# NG001: constructor-established final fields

The parser previously seeded non-null field facts primarily from declaration
initializers. A private final collection assigned in every constructor, or a final
field assigned through Objects.requireNonNull, could still produce NG001 in an
ordinary instance method after normal construction.

`ConstructorFieldFacts` adds a bounded source proof for final instance references.
Every normal exit of every modeled constructor must establish a non-null value.
Supported values include allocation, non-null literals, class literals, local
guarded values, resolved java.util.Objects.requireNonNull and java.util.Arrays.asList.
Resolved this-constructor delegation and simple null-rejection branches are
modeled. Unknown factories and unsupported flow do not establish facts.

The new facts are added to ordinary method entry states, preserving parameter
shadowing. They are separate from framework-injection assumptions and do not
apply to constructors or static methods. Collection contents are not inferred
non-null merely because the collection reference is non-null.

## Boundaries that retain warnings

- Mutable fields, nullable paths, unknown constructor effects and unsupported
  constructor generation do not establish a new final-field fact.
- Serializable/Externalizable classes are excluded because restoration can bypass
  these constructors.
- Unknown or nontrivial superclass construction is excluded. A modeled superclass
  must have empty constructor bodies and no instance fields/initializers.
- Escaping this, anonymous/inner construction captures, lambda/method-reference
  callbacks and open virtual construction dispatch prevent the proof.
- Source private/final methods called during construction are excluded from the
  facts, including their tracked transitive callees. Explicit super method calls
  remain conservative.
- Ordinary methods containing anonymous, lambda or local-class bodies do not
  receive the new facts while deferred-body scope modeling remains limited.
- Constructor delegation, statement/path work and callback audits are bounded;
  a failed or unsupported proof keeps existing warnings.

These facts concern supported normal Java construction. Reflection/Unsafe writes,
runtime instrumentation and arbitrary generated allocation behavior are not
verified by this change. This is not complete escape or heap analysis.

## Regression checks

`ConstructorFieldInvariantTest` failed before the change on Safe.size(). Positive
checks cover multiple collection constructors, requireNonNull assignment and
guarded constructor delegation. Negative checks retain warnings for a nullable
constructor, mutable fields, shadowing parameters, early private reads, static
publication, callback argument publication, superclass and explicit-super
callbacks, mixed branches, swallowed guards, deserialization and deferred scope.

The frozen agent review is not relabeled by the change; project replays are
regression evidence, not independent accuracy estimates.

## Validation and project replays

Final `mvn --batch-mode verify` passed all 11 modules: 191 tests, zero
failures/errors and one skipped opt-in benchmark. The nine Python accuracy
workflow tests and aggregate coverage gates passed (91.9% instructions, 79.1%
branches in this local run). Both candidate SARIF reports passed official-schema
validation. Earlier intermediate builds are retained separately from the final
verification log.

Candidate JAR SHA-256:
`606f335b2adec1120c99a283e42c3a6c2a513be5730904b247f95566d3e48fb2`.
Baseline is the preceding primitive-argument build:
`f49db1ce2670d9aef38babfd14e446130e3a701ec7d9018de6a674de387994df`.
The candidate source base is `b63b000d1ce12dafd1494fbefada092341e5c5dd`
plus the archived constructor-field patch. This source base also includes the
separate memory-insights feature added since the baseline build; comparisons
below concern emitted findings, not all report metadata.

Both checkouts were clean and pinned to the same revisions as earlier rounds.
Runs used Oracle JDK 21.0.9, `-Xmx2g`, main Java sources and no dependency classpath.

| Project | Pinned revision | NG001 before | NG001 after | Added findings |
| --- | --- | ---: | ---: | ---: |
| Gson | `e2292f9c9f2e07a4791827e8eea34aa7d80545d6` | 590 | 535 | 0 |
| Commons Lang | `b9b30efb01e861a16503232bb96b684107e73199` | 1,553 | 1,530 | 0 |
| Total | | 2,143 | 2,065 | 0 |

Gson also loses five NG004 warnings involving proven final FormattingStyle fields
and JsonStreamParser.reader, plus seven remediation notes. Commons Lang loses
three NG004 warnings on CalendarUtils.calendar and five remediation notes.
Risk propagation and contract penalties fall as field warnings are corrected;
the recommendation removals are derived effects, not separately confirmed bugs.
For example FloatAdapter.write's propagated risk falls from about 41.15 to 33.81,
below the NG104 threshold of 40, while its own intrinsic risk remains unchanged.

The frozen agent-assisted Gson sample loses five of its 15 NG001 findings judged
false positives: JsonArray.elements, JsonWriter.out in beforeName/beforeValue,
IntegerFieldsTypeAdapter.fields and the FormattingStyle newline-return chain.
One previously context-dependent ArrayTypeAdapter.componentTypeAdapter warning
also disappears; its original review label is preserved. These changes do not
constitute independent human validation or imply that all remaining warnings are
real defects.

Both reports remain INCOMPLETE. File/body counts are unchanged (Gson: 86 files,
817 analyzed bodies; Commons Lang: 264 files, 4,517 analyzed bodies). Gson retains
3,151 resolved calls out of 3,174. Commons Lang now has 10,377 resolved calls out
of 10,932, versus 10,376 previously: the generic FailableStream constructor call
in Streams.FailableStream.map resolves in this run. Its 71 explicit overflow
limitations are unchanged. No bug-detection improvement is inferred from that
single resolution difference.

[constructor-field-results.json](constructor-field-results.json) records hashes,
commands, revisions, per-rule differences, source accounting and matched review
IDs. Full reports, comparisons, frozen binary, regression logs and source patch
are retained under `.accuracy-work/constructor-fields/`; `evidence.zip` there
packages the evidence outside Maven's clean directory. Working-tree state is
recorded at collection time; the frozen binary identifies the tested candidate.
