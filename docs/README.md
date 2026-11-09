# NullGuard project documentation

NullGuard is an open-source code safety and software risk analysis platform. It helps teams connect source findings to service architecture, API exposure, dependency advisories and test coverage, then prioritize remediation through reports and CI workflows.

## Product scope

The Java engine combines null-state and contract analysis with control-flow models, a global call graph, fixpoint risk propagation, API flow discovery, hotspot detection and stability scoring. SpotBugs and FindSecBugs integration adds security and bug findings from compiled Java classes. Reports include ranked suggestions, source evidence and graph views. Maven and Gradle integrations support gradual adoption through report-only runs and configurable hotspot gates.

Optional signed language packs support experimental Python, JavaScript/TypeScript, C#, Go and Rust analysis. Mixed repositories use independent project analysis; they do not gain a cross-language call graph. Pack findings remain report-only, and capabilities differ from the Java engine.

## Capability guides

| Goal | Documentation |
| --- | --- |
| Install, run and interpret Java analysis | [README and developer guide](../README.md) |
| Analyze mixed repositories and install packs | [Multi-language guide](multilang/README.md) |
| Review dependency advisories and reachability | [CVE analysis](cve-analysis.md) |
| Add test-coverage context | [Coverage and quality](coverage-and-quality.md) |
| Inspect allocation and retention candidates | [Memory insights](memory-insights.md) |
| Understand partial analysis and missing evidence | [Analysis completeness](analysis-completeness.md) |
| Configure Gradle integration | [Gradle guide](../nullguard-gradle-plugin/README.md) |
| Use agent-assisted audits and requested fixes | [NullGuard audit skill](../skills/nullguard-audit/SKILL.md) |
| Evaluate accuracy and known limitations | [Accuracy protocol](evaluation/accuracy-protocol.md) |
| Check artifact availability and version scope | [2.0.0 release notes](releases/2.0.0.md) |
| Understand architecture history | [Architecture reference](versions/nullguard_architecture_v1_1.md) |

## Adoption workflow

1. Choose the integration and language scope, then run in report-only mode.
2. Check parse diagnostics, dependency discovery and analysis completeness before interpreting scores.
3. Review source findings alongside API exposure, downstream risk, available test coverage and advisory evidence.
4. Make a focused fix, add an appropriate regression test and rerun analysis.
5. Introduce Java hotspot gates after reviewing findings and agreeing on the severity threshold.

## Evidence and release boundaries

Static analysis does not run the target application. Memory insights describe source sites and retention candidates, not measured heap usage or confirmed leaks. Advisory matching and call-path evidence do not establish exploitability or a complete dependency inventory. JaCoCo coverage describes supplied test execution data; it does not establish that all source was analyzed.

The CVE guide identifies changes made after the published 2.0.0 artifacts, including online lookup enabled by default in development source. Online lookup sends dependency ecosystems, package names and versions to OSV, without uploading source. Offline mode is available. Check each guide's release scope and installation requirements when selecting an artifact.

Architecture snapshots, dated evaluations and release notes preserve historical context. Use the current developer and capability guides for present behavior.
