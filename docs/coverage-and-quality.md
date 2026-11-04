# Test coverage and code quality dashboard (development)

These features are in the development source and require a new CLI/plugin release.

The generated dashboard has separate **JaCoCo Coverage** and **Code Quality & Fixes** tabs. Existing null analysis, architecture, memory, API, CVE and SAST tabs remain available and are refreshed on each analysis run.

## Import JaCoCo coverage

Run your application's instrumented tests first, then import their XML report:

```sh
java -jar nullguard-cli.jar ./service --jacoco-report=./service/target/site/jacoco/jacoco.xml
```

Without an explicit path, NullGuard checks the analyzed root for `target/site/jacoco-aggregate/jacoco.xml`, `target/site/jacoco/jacoco.xml`, then `build/reports/jacoco/test/jacocoTestReport.xml`. It selects one report to avoid counting aggregate and module data twice. For a multi-module repository, supply its aggregate report explicitly. No tests are executed by the importer.

Maven: `-Dnullguard.jacocoReport=/absolute/path/jacoco.xml`. Gradle extension: `jacocoReport.set(layout.projectDirectory.file("build/reports/jacoco/test/jacocoTestReport.xml"))`.

The tab shows instruction, branch, line, method, class and complexity counters when present, plus per-source uncovered and partially covered line numbers. JaCoCo counts a partially executed line as covered; zero-total counters display N/A. Missing reports display Not available, and invalid reports display an import error. Report path and modification time are shown. NullGuard does not verify that the report matches the current commit. Coverage is imported afresh each run; rerun tests to update the underlying XML.

JaCoCo is a JVM coverage format. Python, JavaScript/TypeScript and .NET coverage formats are not imported by this feature. Their existing analysis remains available. In multi-language reports, coverage is shown per Java project; explicitly supplied reports retain their original scope and are not a per-project coverage allocation.

## Quality review and fixes

The full Java dashboard groups native findings into reliability, security and maintainability and includes SpotBugs findings when a SAST scan is available. Search filters the findings and fix guidance. Rule IDs, severity, locations and reporting tool remain visible. The multi-language preview provides project findings and review guidance in a separate tab.

This is Sonar-style presentation, not a SonarQube or SonarCloud scan, quality gate, remediation-time estimate or automatic patch. Fixes are suggestions requiring source review and tests. CVE version recommendations remain advisory-specific in the existing Reachable CVEs tab. An empty quality table does not prove all checks ran: inspect analysis completeness and SAST/CVE scan status.
