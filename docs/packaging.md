# Lean packaging — 2026-10-16

These changes are in development version 1.0.1-SNAPSHOT, not the published 1.0.0
release. Measurements compare the same local development code before and after
the packaging refactor; they do not directly measure the reported 14.87 MB
published release/download. MB below uses 1,000,000 bytes.

| Artifact | Before | After |
| --- | ---: | ---: |
| Standalone CLI with dependencies | 12.92 MB | 8.52 MB |
| Saved | | 4.41 MB (34.1%) |

The standalone classifier remains `jar-with-dependencies` and still runs using
`java -jar`. No classes were removed through bytecode minimization.

## Dependency separation

`nullguard-engine` now contains the shared `com.nullguard.bootstrap` package and
pipeline integration/benchmark tests. Java package names and bootstrap APIs were
preserved. Both the Maven plugin and CLI depend on this ordinary JAR.
The engine depends on the analysis modules and Jackson, not Maven APIs.
The CLI no longer depends on the Maven plugin, maven-core or maven-plugin-api.
The standalone JAR contains zero `org/apache/maven/` classes; the smoke script
checks that invariant to prevent accidental reintroduction.

The coverage aggregator includes the engine module. Benchmark results now go to
`nullguard-engine/target/benchmark.json`, including the CI artifact path.

## Gradle runtime

`nullguardTool` now resolves the normal CLI JAR with its transitive runtime
dependencies, rather than the standalone classifier. The tool configuration
remains isolated from application dependencies. Maven and Gradle resolve/cache
the individual dependency artifacts normally.

A local development consumer resolved 22 runtime artifacts totaling 8.62 MB and
verified that neither the Maven plugin nor org.apache.maven artifacts were in
that graph. Published 1.0.0 is also supported by the new resolution mechanism,
but retains its old Maven-dependent POM; its dependency graph will not shrink
until a new analyzer release is published.

Caching reduces repeated downloads; it does not remove the parser/Jackson/Guava
runtime dependencies from an initial clean install. The standalone JAR remains
available for environments without Maven or Gradle.

## Verification

Full Maven reactor tests and CLI/Maven smoke checks passed. The smoke checks
exercise standalone execution and Maven goal injection, with expected success
and policy-failure exit codes. The Gradle tests exercise normal dependency
resolution against published 1.0.0, configuration-cache reuse, both DSLs, source
roots, exclusions, duplicate handling, subprojects and build gates. A separate
consumer ran the development thin CLI and found its seeded null dereference.

Coverage gate: 82.8% instructions and 65.5% branches. A parser regression test
also verifies resolution of compiled dependency directories without a Maven
runtime, as used by build-tool integrations.

## Reproduce

```sh
mvn clean install
python3 scripts/smoke_entrypoints.py
python3 scripts/summarize_coverage.py --min-instruction=80 --min-branch=60
cd nullguard-gradle-plugin
./gradlew check
```

Compare actual JAR byte sizes after rebuilding. To try the development thin CLI
from Gradle, make `mavenLocal()` available and set `nullguard.toolVersion` to
`1.0.1-SNAPSHOT`. Continue using immutable release versions for shared CI once
this change has been published.
