# Language support and boundaries

NullGuard combines a Java stability-analysis engine with optional experimental language packs. The same CLI can analyze a mixed repository, with separate results and capability information for each discovered project.

## Choose your language

| Language | Integration | Runtime in addition to Java 21 | Analysis status |
| --- | --- | --- | --- |
| Java | Maven, Gradle, or CLI | Project dependencies improve symbol resolution | Core engine; review completeness and accuracy limits |
| Python | `python` pack | Python 3.11+ | Experimental, report-only |
| JavaScript / JSX | `ecmascript` pack | Node 22+ | Experimental, report-only |
| TypeScript / TSX | `ecmascript` pack | Node 22+; pack pins TypeScript 6.0.3 | Experimental, report-only |
| C# / .NET | `csharp` pack | .NET SDK 10 | Experimental, SDK-style C# only |
| Go | `go` pack | Compatible Go runtime/toolchain | Experimental, report-only |
| Rust | `rust` pack | Compatible stable Cargo toolchain | Experimental, report-only |

Use the exact installed pack manifest and release guide for compatibility. These minimums are not a claim that every newer runtime version has been tested.

## Capabilities differ by language

Java builds control-flow and call graphs, propagates risk through callers, discovers supported Spring MVC/JAX-RS entry points, and produces stability scores and hotspot policies. Its source analysis does not observe runtime execution, dependency bodies, reflection or all generated code.

Non-Java packs report language-specific findings with source evidence, confidence and coverage. They do **not** provide Java-style stability grades, cross-language call graphs, mandatory finding gates, framework OpenAPI generation or vulnerability reachability.

## Important limits

- **Python:** imports, decorators and dynamic attribute contracts are not executed.
- **JavaScript and TypeScript:** project configuration controls the analyzed files; Angular HTML templates and browser/React lifecycle behavior are outside scope.
- **C#:** missing restore assets, generated code and framework dependencies produce gaps. VB/F# and local/lambda CFG bodies are outside scope.
- **Go:** module, build-tag and platform configuration affect coverage; tests are excluded and dependencies are not automatically downloaded.
- **Rust:** no macro expansion, raw-pointer/borrow verification or complete trait-dispatch resolution. Unknown targets remain unknown.

Review the [multi-language reference](../../docs/multilang/README.md), [qualification record](../../docs/multilang/qualification/README.md) and [release notes](../../docs/releases/2.0.0.md). Experimental findings are leads for source review, not independently verified defects or a general precision/recall claim.

## Analyze a mixed repository

```bash
java -jar nullguard-cli.jar . --languages=auto --output=nullguard-out
```

Install the selected signed packs first. Scanning does not restore application dependencies, install packs or execute application tests. Missing packs and partial coverage remain visible failures, with useful results retained when available.
