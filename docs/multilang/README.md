# Multi-language preview — 2.0.0-preview.1

This implementation adds opt-in Python, JavaScript, TypeScript, C#, Go and Rust
analysis alongside Java. All six new languages are experimental. Distribution,
real-application accuracy review and the three-platform installation matrix are
release gates; this source tree is not a published, qualified preview release.

## Installation and quick start

Build the JVM CLI with Java 21: `mvn clean package`. The executable is
`nullguard-cli/target/nullguard-cli-2.0.0-preview.1-jar-with-dependencies.jar`.
Use that path in place of `nullguard-cli.jar` below.

Once signed packs have been published with the release:

```sh
java -jar nullguard-cli.jar packs install python ecmascript csharp go rust
java -jar nullguard-cli.jar packs list
java -jar nullguard-cli.jar . --languages=auto --output=nullguard-out
java -jar nullguard-cli.jar . --languages=python,typescript
java -jar nullguard-cli.jar packs remove python
```

Installation is explicit. Scanning never downloads packs, restores application
dependencies, imports application modules or executes tests/generators. Preparing
an application's dependency assets is a separate developer action. Pack downloads
require GPG/GPGV, a SHA-256 checksum and a detached signature from the bundled
Arinits release public key. Local signed archives can be installed with
`packs install python --from=/absolute/path/archive.zip`; adjacent `.sha256` and
`.asc` files are required. Pack home is `NULLGUARD_PACK_HOME`, otherwise the
platform user data directory. Remove an installed version before replacing it.

Each example has an unsafe case and a guarded case:

| Language | Command after installing its pack |
|---|---|
| Python | `java -jar nullguard-cli.jar examples/multilang/python --languages=python` |
| JavaScript / React JSX | `java -jar nullguard-cli.jar examples/multilang/web --languages=javascript` |
| TypeScript / Angular / React TSX | `java -jar nullguard-cli.jar examples/multilang/web --languages=typescript` |
| C# | `java -jar nullguard-cli.jar examples/multilang/csharp --languages=csharp` |
| Go | `java -jar nullguard-cli.jar examples/multilang/go --languages=go` |
| Rust | `java -jar nullguard-cli.jar examples/multilang/rust --languages=rust` |

For a mixed repository, run `examples/multilang --languages=auto`. Each detected
project is analyzed independently; matching function names or HTTP routes never
create cross-language edges. Dependency/build/generated directories are excluded.
JS/TS files outside the selected project configuration are reported unsupported.

## Reading results

Open `nullguard-out/nullguard-dashboard-latest.html` locally. It contains project
coverage, searchable findings, evidence, dependency advisories and capability
information. Machine outputs are `nullguard-report-latest.json` (schema 2.0) and
`nullguard-results.sarif` (SARIF 2.1.0). Never interpret a partial/failed project as
clean: review failed/unsupported file counts, missing dependencies, unresolved
symbols and exhausted analysis budgets. Unknown symbols cannot establish safety.

A finding has a stable language-specific rule ID, project, source location,
confidence, origin/guard evidence and related locations when available. Definite
and possible failures are distinguished in messages. Current preview findings
are **experimental**, not reviewed high-confidence defects. Review them before
changing code. No general precision/recall claim is made.

Exit status 0 means all projects completed, not that there are no findings.
Status 3 means an analysis/pack/runtime failure or partial coverage; other projects'
results are retained. Status 2 means invalid CLI usage. Preview findings are
report-only; Java's original commands and build gates remain unchanged.

Stability grades, cross-language call graphs, new OpenAPI generation and advisory
reachability are **Not available** for preview packs. Existing positional Java,
Maven and Gradle integrations remain Java-only and preserve schema 1.0 behavior.

## Runtimes and boundaries

| Pack | Required runtime / parser pinned by this implementation | Limits |
|---|---|---|
| Python | Python >=3.11; host `ast` and `symtable` | No module imports, dynamic attributes or decorator execution |
| ECMAScript | Node >=22; TypeScript 6.0.3 compiler API | Host project null settings preserved; no Angular HTML templates or browser lifecycle checks |
| C# | .NET 10; Roslyn 5.0.0 | SDK-style C# only; missing restore/generated/framework assets produce gaps; no VB/F# |
| Go | Compatible installed Go; x/tools v0.52.0 | Module/build-tag/platform dependent; no automatic module download; unresolved calls remain unknown |
| Rust | Stable compatible Cargo; locked syn/cargo_metadata | No macro expansion, unresolved trait dispatch, raw-pointer/borrow verification; standard Option/Result identity required |

Tested locally: macOS arm64, Java 21, Python 3.13, Node 22.22.0,
Go 1.27.2, Rust 1.99.0 and .NET SDK 10.0.401. Linux and Windows execution must
be verified by the qualification workflow before publishing. This is not a claim
that every version above each runtime minimum has been tested.

Dependency inputs: npm/pnpm/Yarn locks, pinned requirements/Poetry/uv, NuGet
restore assets/locks, go.mod and Cargo.lock. Unresolved ranges remain unknown.
Use `--cve-input=/path/advisories.json` for offline advisories or opt into OSV with
`--cve-online`. Exact resolved versions can match advisory records; offline ranges
that cannot be evaluated are diagnosed, and reachability is never inferred.

## Building and qualifying packs

```sh
python3 scripts/build_language_packs.py --sign-key YOUR_RELEASE_KEY_FINGERPRINT
python3 -m unittest discover -s scripts/tests -v
```

The builder creates host-specific archives in `dist-packs/`, with locked compiler
metadata, fixed archive timestamps, checksums and optional detached signatures.
Runtimes and native pack binaries stay outside the Java JAR. Unsigned archives
cannot be installed by the production installer. Publication is a separate action.

Protocol definitions are in `protocol-v1.schema.json` and `report-v2.schema.json`.
The subprocess receives one JSON request on stdin and must return exactly one
version-compatible JSON response. Output, runtime and iteration budgets are
bounded. Failed parsing, malformed output and missing packs are analysis errors.

Before release, review two pinned real applications per language and at least
50 findings per language (or every finding if fewer). Record true/false/uncertain
labels with source evidence separately from regression fixtures. A rule may be
promoted to high confidence only if its reviewed high-confidence sample reaches
90% precision. Pending/unreviewed findings do not count as successful qualification.
See `qualification/README.md` for the current record.

Run `python3 scripts/smoke_multilang.py --assets dist-packs` to verify a clean
signed installation, and `python3 scripts/validate_multilang.py REPORT.json` to
validate schema and coverage accounting (requires jsonschema).
