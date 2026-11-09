# CI adoption

Adapt bundled workflow templates to the consuming repository. Merge jobs into existing CI when practical. Preserve application toolchains, dependency credentials and test commands. Templates assume repository-root projects; change working directories for nested projects and retain per-module outputs.

- `assets/github-actions-maven.yml`: Java 21 analyzer, published plugin 2.0.1, report-only invocation and artifact retention.
- `assets/github-actions-gradle.yml`: configured native Java plugin, existing wrapper, report-only audit helper and artifact retention. Set the analyzer launcher to Java 21 as shown in setup; application compilation may use a separate toolchain.
- `assets/github-actions-multilang.yml`: manual workflow on a provisioned self-hosted runner with an explicit executable CLI and installed signed packs. It does not invent release download URLs. Source scanning does not install Node/.NET/Python or restore application assets. Provision runtimes and compatible signed packs before use.

The templates locate this skill at `skills/nullguard-audit`. Copy the package there or adapt that path. Add project-specific restore/build/test steps where requested; the audit itself does not execute applications. Multi-language findings remain report-only, but partial/failed analysis must still fail the job. Do not blanket `continue-on-error` the scan. Upload reports with `if: always()` while preserving its failure status.

## Explicit Java enforcement

Start with report-only results and review coverage/findings. If enforcement is requested, use the native plugin rather than the report-only helper. Default an unspecified threshold to `CRITICAL`.

Maven:

```sh
./mvnw com.arinits.nullguard:nullguard-maven-plugin:2.0.1:analyze -Dnullguard.failBuild=true -Dnullguard.failThreshold=CRITICAL
```

Gradle: deliberately configure `nullguard { failBuild.set(true); failThreshold.set("CRITICAL") }`, then invoke `./gradlew nullguard` directly. In Groovy use single-quoted strings. Do not use the helper for enforcement because it intentionally forces report-only audit behavior.

The policy gates hotspots (and supported Java API-reachable CVE policy), not every individual finding. Maven/Gradle nonzero exits can mean either enforcement or execution errors; inspect diagnostics before labeling the result. There is no equivalent supported native severity gate for preview language packs.
