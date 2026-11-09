# Get started with NullGuard

NullGuard brings code safety, architectural risk, API exposure, dependency advisories, coverage context and memory insights into a shared review workflow. Inspect the evidence and prioritize fixes across your services and mixed-language repositories. See the [project overview and capability guides](../../docs/README.md) for feature scope and release boundaries.

NullGuard analyzes source and supporting artifacts without running your application. Java provides the full native analysis workflow; optional language packs are experimental and report-only.

Choose a Java build integration or the standalone CLI for a mixed-language repository. Start in report-only mode and review analysis completeness before interpreting a score or a quiet report.

## Java with Maven

Use **Java 21 or newer for the analyzer**. Keep your application's compilation target unchanged. Run from the directory containing `pom.xml`, using `./mvnw` when the project has a wrapper:

```bash
mvn com.arinits.nullguard:nullguard-maven-plugin:2.0.0:analyze \
  -Dnullguard.failBuild=false
```

Open `target/nullguard/nullguard-dashboard-latest.html`. Read `nullguard-report-latest.json` for machine-readable findings and completeness information. Multi-module builds retain per-module scope; separate reports do not establish cross-module flow.

The plugin is [published on Maven Central](https://repo.maven.apache.org/maven2/com/arinits/nullguard/nullguard-maven-plugin/2.0.0/). See the [full Java developer guide](../../README.md) and [Gradle integration](../../nullguard-gradle-plugin/README.md) for configuration, classpaths, reports and explicit build gates.

## Mixed languages with the CLI

Download the signed CLI for your platform from the [2.0.0 release](https://github.com/techbhaskar/null-guard/releases/tag/v2.0.0). Verify the checksum and signature using the release's instructions. You need Java 21 and the runtimes for the language packs you select.

```bash
# Installation is explicit; scanning does not install language packs.
java -jar nullguard-cli.jar packs install python ecmascript csharp
java -jar nullguard-cli.jar packs list

# Analyze the detected projects with the installed packs.
java -jar nullguard-cli.jar . \
  --languages=java,python,javascript,typescript,csharp \
  --output=nullguard-out
```

JS and TS share the `ecmascript` pack. Go and Rust packs are also available. Use `--languages=auto` to select every supported detected language. Each project is analyzed independently; NullGuard does not infer cross-language call chains.

Open `nullguard-out/nullguard-dashboard-latest.html`. The CLI also writes JSON schema 2.0 and SARIF reports. A complete scan can still contain findings and unresolved symbols. Exit code `3` means analysis failed or coverage was partial; retained results must not be mistaken for a clean scan.

Read the [language and runtime matrix](languages.md) and the [full multi-language guide](../../docs/multilang/README.md) before setting up packs. Non-Java packs remain experimental and report-only.

## Make the first fix

1. Check failed and unsupported file counts, unresolved calls, missing dependencies, and exhausted analysis budgets.
2. Select a finding and inspect its actual source and guard evidence. Java reports additionally show supported caller and endpoint exposure.
3. Preserve the intended behavior for missing values. Add a relevant regression test, apply a focused fix, and rerun tests and the same analysis.

An A grade or zero findings does not prove that a project is safe. See the [accuracy protocol](../../docs/evaluation/accuracy-protocol.md) for what the evidence supports.

## Use it through your agent

Install the free [NullGuard audit skill](agent-skill.md) to guide a coding agent through this workflow. It supports Java, Python, JavaScript, TypeScript and C#, including mixed repositories, and includes report-only GitHub Actions templates.
